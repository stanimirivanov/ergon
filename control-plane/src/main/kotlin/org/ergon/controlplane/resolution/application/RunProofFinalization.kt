package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Immutable intent to accept exactly the reviewed match, not whatever matches later.
 * The authenticated adapter supplies [actorId]. Versions must round-trip through
 * browser safe integers; the assessed version leaves room for the closure event.
 */
data class FinalizeRunProofCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val expectedSupervisionRevision: RunSupervisionRevision,
    val expectedRunVersion: Long,
    val expectedCaseVersion: Long,
    val factId: UUID,
    val observationId: UUID,
) {
    init {
        require(expectedRunVersion in 0..2) { "unsupported run version" }
        require(expectedCaseVersion in 1..MAX_RUN_SUPERVISION_REVISION) { "unsupported case version" }
    }
}

/** Original intent and persisted proof instants; replay is historical, never present authority. */
data class StoredRunProofCommand(
    val command: FinalizeRunProofCommand,
    val acceptedAt: Instant,
    val recordedAt: Instant,
)

data class RunProofFinalizationRecording(
    val stored: StoredRunProofCommand,
    val created: Boolean,
)

/**
 * Append-only browser receipt boundary. Caller holds the run lock and transaction.
 * [lockCommand] serializes tenant/command reuse across different runs; [record]
 * links the accepted proof, exact supervision epoch and current resolver attestation.
 * Returns stored timestamps so first application and replay serialize identically.
 */
interface RunProofCommandRepository {
    fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    )

    fun findByCommandId(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunProofCommand?

    fun record(
        command: FinalizeRunProofCommand,
        supervision: CurrentRunSupervision,
        authorityEvidenceId: ApprovalAuthorityEvidenceId,
    ): StoredRunProofCommand
}

class RunProofFinalizationDisabledException : RuntimeException("browser proof finalization is disabled")

class RunProofFinalizationConflictException : RuntimeException("reviewed outcome proof is stale or conflicting")

/**
 * Finalizes one reviewed proof and its case atomically; never invokes a connector.
 * Lock order is run, tenant/command, runtime state, then the existing optimistic
 * case append. Sharing the run lock serializes handover without broadening the
 * legacy internal acceptance boundary. No remote I/O enters this transaction.
 */
@Suppress("LongParameterList") // Explicit authorization, evidence, completion and audit ports have distinct owners.
class RunProofFinalizationService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val assessments: ResolutionOutcomeProofAssessmentService,
    private val completion: ResolutionOutcomeProofCompletion,
    private val commands: RunProofCommandRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
    private val controls: RunCommandControlGate,
) {
    /**
     * Reassesses exact evidence before first acceptance. Changed versions, identities,
     * pending proof or an already completed internal acceptance require a new review.
     * Exact replay requires the original actor and current tenant-wide RESOLVER
     * authority, but not continued supervision; it cannot apply a second closure.
     * [control] retains the original public fence and trusted session binding; expiry rolls back provisional case
     * closure.
     * @throws RunControlRequiredException when enforced first admission lacks control.
     * @throws RunControlConflictException for expired/mismatched control or changed replay fence.
     *
     * @throws AssignedRunNotFoundException for private absence, lost access or foreign receipts.
     * @throws RunProofFinalizationConflictException for stale intent or evidence.
     * @throws RunProofFinalizationDisabledException while rollout is disabled.
     */
    fun finalizeProof(
        command: FinalizeRunProofCommand,
        control: RunCommandControl? = null,
    ): RunProofFinalizationRecording =
        transactions.required {
            if (!enabled) throw RunProofFinalizationDisabledException()
            val key =
                RunControlCommandKey(
                    command.tenantId,
                    command.runId,
                    RunControlCommandKind.PROOF,
                    command.commandId,
                )
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            requireResolver(tenant, actor)
            commands.lockCommand(tenant, command.commandId)
            commands.findByCommandId(tenant, command.commandId)?.let { stored ->
                requireResolver(tenant, actor)
                if (stored.command.actorId != command.actorId || stored.command.runId != command.runId) {
                    throw AssignedRunNotFoundException()
                }
                if (stored.command != command) throw RunProofFinalizationConflictException()
                controls.replay(key, control)
                return@required RunProofFinalizationRecording(stored, created = false)
            }
            recordFirst(command, tenant, run, actor).also {
                controls.admit(key, command.actorId, command.expectedSupervisionRevision, control)
            }
        }

    private fun recordFirst(
        command: FinalizeRunProofCommand,
        tenant: TenantId,
        run: ResolutionRunId,
        actor: HumanActorId,
    ): RunProofFinalizationRecording {
        val state = transitions.lockState(tenant, run) ?: error("assigned run state is absent")
        val current =
            assignments.findAssigned(tenant, run, actor, clock.instant()) ?: throw AssignedRunNotFoundException()
        if (current.revision != command.expectedSupervisionRevision || state.version != command.expectedRunVersion ||
            state.state != ResolutionRunState.VERIFYING
        ) {
            throw RunProofFinalizationConflictException()
        }
        val assessment = requireReviewedMatch(command)
        // Lock waits and assessment work cannot preserve an expired authority check.
        // Preserve case JSON's nanoseconds; flooring the event could put it
        // before a just-recorded binding. Authority boundaries use SQL precision.
        val acceptedAt = clock.instant()
        val evidence = requireResolver(tenant, actor, acceptedAt)
        val start = checkNotNull(runs.find(tenant, run)).run
        appendAssessedProof(tenant, start, state, assessment, acceptedAt)
        return RunProofFinalizationRecording(commands.record(command, current, evidence), created = true)
    }

    private fun requireReviewedMatch(command: FinalizeRunProofCommand): ResolutionOutcomeProofAssessment.Accepted {
        val assessment =
            assessments.assess(command.tenantId, command.runId) as? ResolutionOutcomeProofAssessment.Accepted
                ?: throw RunProofFinalizationConflictException()
        val sameEvidence =
            assessment.evidence.factId.value == command.factId &&
                assessment.evidence.observationId.value == command.observationId
        if (assessment.caseStreamVersion != command.expectedCaseVersion || !sameEvidence) {
            throw RunProofFinalizationConflictException()
        }
        return assessment
    }

    private fun appendAssessedProof(
        tenant: TenantId,
        start: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        assessment: ResolutionOutcomeProofAssessment.Accepted,
        acceptedAt: Instant,
    ) {
        try {
            val event = completion.stageAt(tenant, start, state, assessment, acceptedAt)
            transitions.appendAcceptedProof(tenant, start.caseId, event)
        } catch (_: ResolutionOutcomeProofChangedException) {
            throw RunProofFinalizationConflictException()
        }
    }

    private fun requireResolver(
        tenant: TenantId,
        actor: HumanActorId,
        at: Instant = clock.instant().truncatedTo(ChronoUnit.MICROS),
    ): ApprovalAuthorityEvidenceId =
        authorities
            .findCurrent(
                tenant,
                actor,
                ApprovalAuthority.RESOLVER,
                null,
                at.truncatedTo(ChronoUnit.MICROS),
            )?.evidence
            ?.id ?: throw AssignedRunNotFoundException()
}
