package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.ContradictionFinding
import org.ergon.evidence.domain.DocumentaryClaimKey
import org.ergon.evidence.domain.DocumentaryContradictions
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AcceptedClaimCandidate(
    val stored: StoredClaimProposal,
    val reviewCommandId: UUID,
)

data class AcceptedClaimSet(
    val key: DocumentaryClaimKey,
    val revision: Long,
    val candidates: List<AcceptedClaimCandidate>,
)

data class AssessedClaimSet(
    val set: AcceptedClaimSet,
    val findings: List<ContradictionFinding>,
)

data class StoredContradictionAdjudication(
    val command: ContradictionCommand,
    val key: DocumentaryClaimKey,
    val reviewCommands: List<UUID>,
    val findingCodes: List<ClaimComparison>,
    val reviewer: HumanActorId,
    val authority: ApprovalAuthorityEvidenceId,
    val recordedAt: Instant,
)

data class ContradictionAdjudicationView(
    val stored: StoredContradictionAdjudication,
    val assessment: AssessedClaimSet,
    val current: Boolean,
)

data class ContradictionRegistration(
    val view: ContradictionAdjudicationView,
    val created: Boolean,
)

class InvalidContradiction : RuntimeException()

class ContradictionConflict : RuntimeException()

class ContradictionSetTooLarge : RuntimeException()

/** Snapshots and admission serialize with acceptance. Stored arrays preserve canonical pair order under pinned v1. */
interface ContradictionRepository {
    fun capture(
        tenant: TenantId,
        anchor: UUID,
    ): AcceptedClaimSet

    fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredContradictionAdjudication?

    fun currentRevision(
        tenant: TenantId,
        key: DocumentaryClaimKey,
    ): Long

    fun candidate(
        tenant: TenantId,
        id: UUID,
    ): AcceptedClaimCandidate?

    fun register(
        admission: EvidenceReviewAdmission,
        assessment: AssessedClaimSet,
        command: ContradictionCommand,
    ): Pair<StoredContradictionAdjudication, Boolean>
}

/** Every private access reauthorizes; source verification occurs outside short database admission transactions. */
class ContradictionService(
    private val authorities: HumanAuthorityRepository,
    private val sources: SourceRevisionService,
    private val repository: ContradictionRepository,
    private val clock: Clock,
) {
    fun assess(
        tenant: TenantId,
        actor: HumanActorId,
        anchor: UUID,
    ): AssessedClaimSet {
        authorize(tenant, actor)
        return assessVerified(repository.capture(tenant, anchor), actor)
    }

    fun adjudicate(
        tenant: TenantId,
        actor: HumanActorId,
        command: ContradictionCommand,
    ): ContradictionRegistration {
        val admission = authorize(tenant, actor)
        val previous = repository.find(tenant, command.id)
        if (previous != null) {
            if (previous.command != command || previous.reviewer != actor) throw ContradictionConflict()
            // Repository admission rechecks authority after locks even on historical receipt recovery.
            val historical = historical(tenant, actor, previous)
            val result = repository.register(admission, historical, command)
            return registration(tenant, historical, result)
        }
        val assessment = assessVerified(repository.capture(tenant, command.anchorId), actor)
        validateIntent(assessment, command)
        val result = repository.register(admission, assessment, command)
        return registration(tenant, assessment, result)
    }

    private fun validateIntent(
        assessment: AssessedClaimSet,
        command: ContradictionCommand,
    ) {
        val ids = assessment.set.candidates.map { it.stored.proposal.id }
        if (assessment.set.revision != command.expectedRevision || ids != command.candidateIds) {
            throw ContradictionConflict()
        }
        if (assessment.findings.none { it.comparison in REVIEWABLE }) throw InvalidContradiction()
    }

    private fun registration(
        tenant: TenantId,
        assessment: AssessedClaimSet,
        result: Pair<StoredContradictionAdjudication, Boolean>,
    ): ContradictionRegistration =
        ContradictionRegistration(
            ContradictionAdjudicationView(
                result.first,
                assessment,
                repository.currentRevision(tenant, result.first.key) == result.first.command.expectedRevision,
            ),
            result.second,
        )

    fun get(
        tenant: TenantId,
        actor: HumanActorId,
        id: UUID,
    ): ContradictionAdjudicationView {
        authorize(tenant, actor)
        return view(tenant, actor, repository.find(tenant, id) ?: throw SourceUnavailable())
    }

    private fun view(
        tenant: TenantId,
        actor: HumanActorId,
        stored: StoredContradictionAdjudication,
    ): ContradictionAdjudicationView =
        ContradictionAdjudicationView(
            stored,
            historical(tenant, actor, stored),
            repository.currentRevision(tenant, stored.key) == stored.command.expectedRevision,
        )

    private fun historical(
        tenant: TenantId,
        actor: HumanActorId,
        stored: StoredContradictionAdjudication,
    ): AssessedClaimSet {
        val candidates =
            stored.command.candidateIds.map {
                repository.candidate(tenant, it) ?: throw SourceUnavailable()
            }
        check(candidates.map { it.reviewCommandId } == stored.reviewCommands) {
            "stored review identities are inconsistent"
        }
        check(candidates.all { DocumentaryClaimKey.of(it.stored.proposal.claim) == stored.key })
        val assessed = assessVerified(AcceptedClaimSet(stored.key, stored.command.expectedRevision, candidates), actor)
        check(assessed.findings.map { it.comparison } == stored.findingCodes) { "stored findings are inconsistent" }
        return assessed
    }

    private fun assessVerified(
        set: AcceptedClaimSet,
        actor: HumanActorId,
    ): AssessedClaimSet {
        if (set.candidates.size > DocumentaryContradictions.MAX_CANDIDATES) throw ContradictionSetTooLarge()
        for (candidate in set.candidates) {
            val proposal = candidate.stored.proposal
            val source = sources.get(proposal.citation.revision, actor).source
            check(proposal.citation.verifyAgainst(source)) { "stored contradiction citation is inconsistent" }
        }
        return AssessedClaimSet(set, DocumentaryContradictions.assess(set.candidates.map { it.stored.proposal }))
    }

    private fun authorize(
        tenant: TenantId,
        actor: HumanActorId,
    ): EvidenceReviewAdmission {
        val proof =
            authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
                ?: throw SourceUnavailable()
        return EvidenceReviewAdmission(tenant, actor, proof.evidence.id)
    }

    companion object {
        private val REVIEWABLE = setOf(ClaimComparison.CONFLICT, ClaimComparison.UNDETERMINED)
    }
}
