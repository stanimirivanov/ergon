package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionOutcomeProofBasis
import org.ergon.resolution.domain.ResolutionOutcomeProofEvaluator
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.util.UUID

/** Signals that outcome proof cannot be assessed before successful action execution. */
class ResolutionRunNotVerifyingException(
    runId: UUID,
    val state: String,
) : RuntimeException("resolution run $runId cannot assess outcome proof while state is $state")

/** Evaluates a run's pinned proof condition against attributable post-action case evidence. */
class ResolutionOutcomeProofAssessmentService(
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val contracts: ResolutionContractRevisionRepository,
    private val eventStore: CaseEventStore,
) {
    /**
     * Assesses [runId] at the latest tenant-scoped case stream version.
     *
     * Only evidence whose source observation was captured after both the run's
     * pinned case boundary and connector completion is eligible. The result is
     * a read-only assessment: even accepted proof does not advance run or case
     * state.
     *
     * @throws ResolutionRunNotFoundException when [runId] is absent from [tenantId].
     * @throws ResolutionRunNotVerifyingException unless successful execution
     *   has advanced the run to `VERIFYING`.
     * @throws IllegalStateException when constrained durable run dependencies
     *   cannot be recovered.
     */
    fun assess(
        tenantId: UUID,
        runId: UUID,
    ): ResolutionOutcomeProofAssessment {
        val scopedTenantId = TenantId(tenantId)
        val scopedRunId = ResolutionRunId(runId)
        val run =
            runs.find(scopedTenantId, scopedRunId)?.run
                ?: throw ResolutionRunNotFoundException(runId)
        val state =
            checkNotNull(transitions.findState(scopedTenantId, scopedRunId)) {
                "resolution run current-state projection is missing"
            }
        if (state.state != ResolutionRunState.VERIFYING) {
            throw ResolutionRunNotVerifyingException(runId, state.state.name)
        }
        val receipt =
            checkNotNull(receipts.findByRun(scopedTenantId, scopedRunId)) {
                "verifying resolution run has no capability receipt"
            }.receipt
        val contract =
            checkNotNull(contracts.find(scopedTenantId, run.contract.key, run.contract.revision)) {
                "resolution run contract revision cannot be recovered"
            }.contract
        val history = eventStore.load(scopedTenantId, run.caseId)
        check(history.isNotEmpty()) { "resolution run case history cannot be recovered" }
        return ResolutionOutcomeProofEvaluator.evaluate(
            ResolutionOutcomeProofBasis(
                condition = contract.outcomeProof,
                runCaseStreamVersion = run.caseStreamVersion,
                actionCompletedAt = receipt.completedAt,
                caseStreamVersion = history.size.toLong(),
                evidence = history.toOutcomeEvidence(),
            ),
        )
    }
}

private fun List<CaseEvent>.toOutcomeEvidence(): List<ResolutionOutcomeEvidence> {
    val observations =
        mapIndexedNotNull { index, event ->
            (event as? ObservationRecorded)?.let {
                it.observationId to IndexedObservation(index + 1L, it)
            }
        }.toMap()
    return mapIndexedNotNull { index, event ->
        val fact = event as? AccountAccessStateBound ?: return@mapIndexedNotNull null
        val observation = observations[fact.observationId] ?: return@mapIndexedNotNull null
        ResolutionOutcomeEvidence(
            factId = FactId(fact.factId),
            fact = ACCOUNT_ACCESS_STATE,
            value = ContractFactValue.of(fact.state.name),
            observationId = ObservationId(fact.observationId),
            observationStreamVersion = observation.streamVersion,
            factStreamVersion = index + 1L,
            observedAt = observation.event.occurredAt,
            boundAt = fact.occurredAt,
        )
    }
}

private data class IndexedObservation(
    val streamVersion: Long,
    val event: ObservationRecorded,
)

private val ACCOUNT_ACCESS_STATE = ContractFactType.of("account.access.state")
