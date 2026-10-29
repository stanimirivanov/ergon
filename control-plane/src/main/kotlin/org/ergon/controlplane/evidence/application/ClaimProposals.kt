package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

/** Original proposer/time survive exact retries; acceptance belongs to a later review capability. */
data class StoredClaimProposal(
    val proposal: ClaimProposal,
    val proposedBy: HumanActorId,
    val recordedAt: Instant,
)

data class ClaimRegistration(
    val stored: StoredClaimProposal,
    val created: Boolean,
)

/** Implementations atomically recover exact retries and reject changed commands with [ClaimConflict]. */
interface ClaimProposalRepository {
    fun register(
        proposal: ClaimProposal,
        actor: HumanActorId,
    ): ClaimRegistration

    fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredClaimProposal?
}

/** Caller intent excludes quotations and compiler revision; provenance is selected server-side. */
data class ClaimProposalCommand(
    val id: UUID,
    val extractionId: UUID,
    val observationIndex: Int,
    val claim: DocumentaryClaim,
)

class ClaimConflict : RuntimeException()

class InvalidClaim : RuntimeException()

/** Binds an explicitly selected Markdown observation, with authorization owned by the source use case. */
class ClaimProposalService(
    private val sources: SourceRevisionService,
    private val compilation: SourceCompilationService,
    private val repository: ClaimProposalRepository,
) {
    /** Recomputes exact provenance; invalid extraction/selection never reaches proposal storage. */
    fun propose(
        source: SourceRevision,
        actor: HumanActorId,
        command: ClaimProposalCommand,
    ): ClaimRegistration {
        val result = compilation.compile(source, actor)
        val observation = result.observations.getOrNull(command.observationIndex) ?: throw InvalidClaim()
        val matches = result.extraction.id == command.extractionId && observation.citation.revision == source
        if (!matches) throw InvalidClaim()
        val original = sources.get(source, actor).source
        check(observation.citation.verifyAgainst(original)) { "compiled claim citation is inconsistent" }
        val proposal =
            ClaimProposal(
                command.id,
                result.extraction,
                command.observationIndex,
                observation.citation,
                command.claim,
            )
        return repository.register(proposal, actor)
    }

    /** Missing, foreign and wrong-source identities are indistinguishable after current source authorization. */
    fun get(
        source: SourceRevision,
        actor: HumanActorId,
        id: UUID,
    ): StoredClaimProposal {
        val original = sources.get(source, actor).source
        val stored = repository.find(source.tenantId, id) ?: throw SourceUnavailable()
        if (stored.proposal.citation.revision != source) throw SourceUnavailable()
        check(stored.proposal.citation.verifyAgainst(original)) { "stored claim citation is inconsistent" }
        return stored
    }
}
