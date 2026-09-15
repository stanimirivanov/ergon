package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.VerifiedResolution
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.CaseProjectionWriter
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.cases.application.IdentityGenerator
import org.ergon.controlplane.cases.application.NewCaseEvent
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionOutcomeProofAcceptanceBasis
import org.ergon.resolution.domain.ResolutionOutcomeProofAccepted
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionOutcomeProofPendingReason
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.util.UUID

/** Signals that the latest eligible evidence does not satisfy the pinned proof condition. */
class ResolutionOutcomeProofPendingException(
    val reason: ResolutionOutcomeProofPendingReason,
    val caseStreamVersion: Long,
) : RuntimeException("resolution outcome proof is pending: $reason at case version $caseStreamVersion")

/** Signals that case evidence changed while an accepted proof was being committed. */
class ResolutionOutcomeProofChangedException(
    val assessedVersion: Long,
    val actualVersion: Long,
    cause: Throwable? = null,
) : RuntimeException("outcome proof assessed case version $assessedVersion but found $actualVersion", cause)

/** Creates accepted-proof events with application-owned time and identity sources. */
class ResolutionOutcomeProofAcceptedEventFactory(
    private val identities: ResolutionRunEventIdentityGenerator,
    private val clock: Clock,
) {
    /** @return a new terminal event freezing [assessment] for [run]. */
    fun create(
        run: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        assessment: ResolutionOutcomeProofAssessment.Accepted,
    ): ResolutionOutcomeProofAccepted =
        ResolutionOutcomeProofAccepted.record(
            identities.next(),
            ResolutionOutcomeProofAcceptanceBasis(run, state, assessment, clock.instant()),
        )
}

/** Writes one verified case closure against the exact case version accepted by a run event. */
class ResolutionOutcomeCaseCloser(
    private val eventStore: CaseEventStore,
    private val projectionWriter: CaseProjectionWriter,
    private val identities: IdentityGenerator,
) {
    /**
     * Appends the case closure represented by [event] inside the caller's transaction.
     *
     * @throws ResolutionOutcomeProofChangedException when later case evidence
     *   makes [event]'s assessment stale.
     */
    fun close(
        tenantId: TenantId,
        caseId: CaseId,
        event: ResolutionOutcomeProofAccepted,
    ) {
        val history = eventStore.load(tenantId, caseId)
        if (history.size.toLong() != event.caseStreamVersion) {
            throw ResolutionOutcomeProofChangedException(event.caseStreamVersion, history.size.toLong())
        }
        val case = ErgonCase.rehydrate(caseId, tenantId, history)
        case.verifyResolved(
            VerifiedResolution(
                resolutionRunId = event.runId.value,
                outcomeProofEventId = event.id.value,
                proofCaseStreamVersion = event.caseStreamVersion,
                factId = event.factId,
                observationId = event.observationId,
            ),
            resolvedAt = event.acceptedAt,
        )
        val events = case.pendingEvents().map { NewCaseEvent(identities.next(), it) }
        val stored =
            try {
                eventStore.append(tenantId, case.id, event.caseStreamVersion, events)
            } catch (exception: ConcurrentCaseModificationException) {
                throw ResolutionOutcomeProofChangedException(
                    exception.expectedVersion,
                    exception.actualVersion,
                    exception,
                )
            }
        projectionWriter.project(case, stored)
    }
}

/** Creates the terminal run event and applies its paired case closure. */
class ResolutionOutcomeProofCompletion(
    private val eventFactory: ResolutionOutcomeProofAcceptedEventFactory,
    private val caseCloser: ResolutionOutcomeCaseCloser,
) {
    /** @return the accepted-proof event after staging its exact case closure. */
    fun stage(
        tenantId: TenantId,
        run: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        assessment: ResolutionOutcomeProofAssessment.Accepted,
    ): ResolutionOutcomeProofAccepted =
        eventFactory.create(run, state, assessment).also { event ->
            caseCloser.close(tenantId, run.caseId, event)
        }
}

/** Atomically completes a verifying run and its case from exact accepted evidence. */
class ResolutionOutcomeProofAcceptanceService(
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val assessments: ResolutionOutcomeProofAssessmentService,
    private val completion: ResolutionOutcomeProofCompletion,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Freezes currently accepted proof and closes its run and case in one transaction.
     *
     * Replay returns the original accepted-proof event. A concurrent case
     * append invalidates the assessment and rolls back both state transitions.
     *
     * @throws ResolutionRunNotFoundException when [runId] is absent from [tenantId].
     * @throws ResolutionRunNotVerifyingException unless the run is verifying
     *   or already contains the accepted-proof event being replayed.
     * @throws ResolutionOutcomeProofPendingException when current eligible
     *   evidence does not satisfy the pinned condition.
     * @throws ResolutionOutcomeProofChangedException when case evidence changes
     *   before the accepted decision is committed.
     */
    fun accept(
        tenantId: UUID,
        runId: UUID,
    ): ResolutionOutcomeProofAcceptanceRecording =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedRunId = ResolutionRunId(runId)
            val run =
                runs.find(scopedTenantId, scopedRunId)?.run
                    ?: throw ResolutionRunNotFoundException(runId)
            val state =
                checkNotNull(transitions.lockState(scopedTenantId, scopedRunId)) {
                    "resolution run current-state projection is missing"
                }
            transitions.findAcceptedProof(scopedTenantId, scopedRunId)?.let { accepted ->
                check(state.state == ResolutionRunState.VERIFIED_RESOLVED && state.version == 2L) {
                    "accepted proof and resolution run state disagree"
                }
                return@required ResolutionOutcomeProofAcceptanceRecording(accepted, state, created = false)
            }
            if (state.state != ResolutionRunState.VERIFYING) {
                throw ResolutionRunNotVerifyingException(runId, state.state.name)
            }

            val assessment = assessments.assess(tenantId, runId)
            if (assessment is ResolutionOutcomeProofAssessment.Pending) {
                throw ResolutionOutcomeProofPendingException(assessment.reason, assessment.caseStreamVersion)
            }
            val accepted = assessment as ResolutionOutcomeProofAssessment.Accepted
            val runEvent = completion.stage(scopedTenantId, run, state, accepted)
            transitions.appendAcceptedProof(scopedTenantId, run.caseId, runEvent)
        }
}
