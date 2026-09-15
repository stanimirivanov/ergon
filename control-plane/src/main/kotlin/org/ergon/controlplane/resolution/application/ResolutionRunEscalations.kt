package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationBasis
import org.ergon.resolution.domain.ResolutionRunEscalationRequested
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Immutable escalation event paired with its database recording instant. */
data class StoredResolutionRunEscalation(
    val event: ResolutionRunEscalationRequested,
    val recordedAt: Instant,
)

/** Result of first recording or idempotently replaying one escalation. */
data class ResolutionRunEscalationRecording(
    val storedEvent: StoredResolutionRunEscalation,
    val currentState: ResolutionRunStateSnapshot,
    val created: Boolean,
)

/** Durable append and replay boundary for exhausted-run escalations. */
interface ResolutionRunEscalationRepository {
    /** @return the escalation recorded for [runId], or `null` before escalation. */
    fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunEscalation?

    /**
     * Appends [event] and advances its current-state projection atomically.
     *
     * The caller must retain the state lock obtained before constructing the event.
     */
    fun append(
        tenantId: TenantId,
        event: ResolutionRunEscalationRequested,
    ): ResolutionRunEscalationRecording
}

/** Authoritative records consulted while requesting exhausted-run escalation. */
data class ResolutionRunEscalationRecords(
    val runs: ResolutionRunRepository,
    val transitions: ResolutionRunTransitionRepository,
    val escalations: ResolutionRunEscalationRepository,
    val authorities: HumanAuthorityRepository,
)

/** Signals that the run is not an unhandled failed attempt. */
class ResolutionRunEscalationStateException(
    val state: String,
) : RuntimeException("resolution run cannot escalate while state is $state")

/** Signals that retry policy still permits a successor for the failed attempt. */
class ResolutionRunRetryBudgetAvailableException(
    val policyRevision: ResolutionRetryPolicyRevision,
    val sourceAttemptNumber: Int,
    val maximumAttempts: Int,
) : RuntimeException(
        "retry policy ${policyRevision.value} still permits a successor after attempt $sourceAttemptNumber",
    )

/** Terminates exhausted automated recovery as explicit, attributable human follow-up. */
class ResolutionRunEscalationService(
    private val records: ResolutionRunEscalationRecords,
    private val retryPolicy: ResolutionRetryPolicy,
    private val eventIdentities: ResolutionRunEventIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Requests or replays escalation for one exhausted failed run.
     *
     * The authenticated [actorId] needs current tenant-wide resolver evidence.
     * First recording changes only run state; the case remains open and no
     * assignment, notification, compensation, or capability invocation occurs.
     *
     * @throws ResolutionRunNotFoundException when [runId] is absent from [tenantId].
     * @throws ResolutionRunEscalationStateException unless the run is `ACTION_FAILED`.
     * @throws ResolutionRunRetryBudgetAvailableException while retry policy still
     *   permits another attempt.
     * @throws CurrentResolutionRecoveryAuthorityNotFoundException when the actor
     *   lacks current tenant-wide resolver evidence.
     */
    fun escalate(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): ResolutionRunEscalationRecording =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedRunId = ResolutionRunId(runId)
            // Match PostgreSQL precision so the first response and durable replay agree.
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val authority = records.requireRecoveryAuthority(scopedTenantId, HumanActorId(actorId), now)
            val run = records.requireRun(scopedTenantId, scopedRunId)
            val state =
                checkNotNull(records.transitions.lockState(scopedTenantId, scopedRunId)) {
                    "resolution run current-state projection is missing"
                }
            records.escalations.find(scopedTenantId, scopedRunId)?.let { existing ->
                check(state.state == ResolutionRunState.ESCALATED && state.version == 2L) {
                    "resolution run escalation contradicts its current-state projection"
                }
                return@required ResolutionRunEscalationRecording(existing, state, created = false)
            }
            if (state.state != ResolutionRunState.ACTION_FAILED || state.version != 1L) {
                throw ResolutionRunEscalationStateException(state.state.name)
            }
            val denial = retryPolicy.requireExhausted(run.attemptNumber)
            val event = createEscalation(run, state, authority, denial, now)
            records.escalations.append(scopedTenantId, event)
        }

    private fun createEscalation(
        run: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        authority: ApprovalAuthorityEvidence,
        denial: ResolutionRetryEligibility.Denied,
        at: Instant,
    ): ResolutionRunEscalationRequested =
        try {
            ResolutionRunEscalationRequested.request(
                eventIdentities.next(),
                ResolutionRunEscalationBasis(run, state, authority, denial),
                at,
            )
        } catch (exception: IllegalArgumentException) {
            // These are durable sources; disagreement means an internal contract was broken.
            throw IllegalStateException("stored resolution escalation sources are inconsistent", exception)
        }
}

private fun ResolutionRetryPolicy.requireExhausted(sourceAttemptNumber: Int): ResolutionRetryEligibility.Denied =
    when (val decision = evaluate(sourceAttemptNumber)) {
        is ResolutionRetryEligibility.Denied -> {
            decision
        }

        is ResolutionRetryEligibility.Eligible -> {
            throw ResolutionRunRetryBudgetAvailableException(
                decision.revision,
                decision.sourceAttemptNumber,
                decision.maximumAttempts,
            )
        }
    }

private fun ResolutionRunEscalationRecords.requireRecoveryAuthority(
    tenantId: TenantId,
    actorId: HumanActorId,
    at: Instant,
): ApprovalAuthorityEvidence =
    authorities
        .findCurrent(
            tenantId = tenantId,
            actorId = actorId,
            authority = ApprovalAuthority.RESOLVER,
            caseId = null,
            at = at,
        )?.evidence ?: throw CurrentResolutionRecoveryAuthorityNotFoundException()

private fun ResolutionRunEscalationRecords.requireRun(
    tenantId: TenantId,
    runId: ResolutionRunId,
): ResolutionRunStart = runs.find(tenantId, runId)?.run ?: throw ResolutionRunNotFoundException(runId.value)
