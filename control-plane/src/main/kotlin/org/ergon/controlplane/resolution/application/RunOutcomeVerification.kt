package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseVerifiedResolved
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.contracts.domain.FactCondition
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionOutcomeProofBasis
import org.ergon.resolution.domain.ResolutionOutcomeProofEvaluator
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Instant

/** A current match is deliberately not a durable acceptance or permission to close the case. */
enum class RunVerificationStatus { NOT_ASSESSED, PENDING, MATCHING, ACCEPTED }

/**
 * One pinned condition and, only when selected, its exact post-action evidence.
 * [caseStreamVersion] is the assessed version, not the run's evidence pin or a count of checks.
 * Only ACCEPTED has [acceptance]; missing eligibility has no [evidence].
 */
data class RunOutcomeVerification(
    val status: RunVerificationStatus,
    val condition: FactCondition,
    val caseStreamVersion: Long? = null,
    val reason: String? = null,
    val evidence: RunVerificationEvidence? = null,
    val acceptance: RunProofAcceptance? = null,
)

/** Exact binding and connector observation selected by assessment or immutable acceptance, never a transcript. */
data class RunVerificationEvidence(
    val fact: ResolutionOutcomeEvidence,
    val accountReference: String,
    val source: ObservationRecorded,
)

/** Durable decision times and paired case closure; no private event or authority identity is disclosed. */
data class RunProofAcceptance(
    val runSequence: Long,
    val acceptedAt: Instant,
    val recordedAt: Instant,
    val caseClosureStreamVersion: Long,
)

/**
 * Projects verification only after the caller establishes current supervisor/resolver access.
 * Reuses the runtime evaluator without executing a command. Case events are loaded internally,
 * but only the selected binding/source may cross the browser boundary. All positions are checked
 * against that single loaded history. A later acceptance cannot enter an older state prefix.
 */
class RunOutcomeVerificationService(
    private val contracts: ResolutionContractRevisionRepository,
    private val cases: CaseEventStore,
    private val transitions: ResolutionRunTransitionRepository,
) {
    /**
     * Returns a snapshot aligned with [state], not continuous verification or revocation.
     * Persisted dependency/attribution disagreement throws [IllegalStateException], never partial proof.
     */
    fun read(
        tenant: TenantId,
        run: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        receipt: StoredCapabilityInvocationReceipt?,
    ): RunOutcomeVerification {
        val condition =
            checkNotNull(contracts.find(tenant, run.contract.key, run.contract.revision)).contract.outcomeProof
        if (state.state != ResolutionRunState.VERIFYING && state.state != ResolutionRunState.VERIFIED_RESOLVED) {
            return RunOutcomeVerification(RunVerificationStatus.NOT_ASSESSED, condition, reason = "RUN_NOT_VERIFYING")
        }
        val action = checkNotNull(receipt).receipt
        check(action.outcome == CapabilityInvocationOutcome.SUCCEEDED) { "verification requires successful execution" }
        val history = cases.load(tenant, run.caseId)
        check(history.size.toLong() >= run.caseStreamVersion) { "verification case history is incomplete" }
        return if (state.state == ResolutionRunState.VERIFIED_RESOLVED) {
            accepted(tenant, run, condition, history, action.completedAt).also {
                check(it.acceptance?.runSequence == state.version && it.acceptance.acceptedAt == state.updatedAt)
            }
        } else {
            val assessment =
                ResolutionOutcomeProofEvaluator.evaluate(
                    ResolutionOutcomeProofBasis(
                        condition,
                        run.caseStreamVersion,
                        action.completedAt,
                        history.size.toLong(),
                        history.toOutcomeEvidence(),
                    ),
                )
            when (assessment) {
                is ResolutionOutcomeProofAssessment.Accepted -> {
                    RunOutcomeVerification(
                        RunVerificationStatus.MATCHING,
                        condition,
                        assessment.caseStreamVersion,
                        evidence = history.selected(assessment.evidence),
                    )
                }

                is ResolutionOutcomeProofAssessment.Pending -> {
                    RunOutcomeVerification(
                        RunVerificationStatus.PENDING,
                        condition,
                        assessment.caseStreamVersion,
                        assessment.reason.name,
                        assessment.evidence?.let { history.selected(it) },
                    )
                }
            }
        }
    }

    private fun accepted(
        tenant: TenantId,
        run: ResolutionRunStart,
        condition: FactCondition,
        history: List<CaseEvent>,
        completedAt: Instant,
    ): RunOutcomeVerification {
        val stored = checkNotNull(transitions.findAcceptedProof(tenant, run.id))
        val event = stored.event
        check(event.runId == run.id)
        check(
            event.runCaseStreamVersion == run.caseStreamVersion && event.fact == condition.fact &&
                event.expectedValue == condition.expectedValue,
        )
        val selected = checkNotNull(history.toOutcomeEvidence().find { it.factId == event.factId })
        check(
            selected.observationId == event.observationId &&
                selected.observationStreamVersion == event.observationStreamVersion &&
                selected.factStreamVersion == event.factStreamVersion && selected.value == condition.expectedValue,
        )
        check(selected.observedAt >= completedAt)
        check(event.caseStreamVersion < history.size.toLong()) { "accepted case closure is absent" }
        val closure = history.getOrNull(event.caseStreamVersion.toInt()) as? CaseVerifiedResolved
        check(
            closure != null && closure.resolutionRunId == run.id.value &&
                closure.outcomeProofEventId == event.id.value &&
                closure.proofCaseStreamVersion == event.caseStreamVersion && closure.factId == event.factId.value &&
                closure.observationId == event.observationId.value,
        )
        // The linked decision identities/positions are authoritative. Case JSON preserves
        // nanoseconds while relational acceptance/state timestamps have storage precision;
        // comparing those occurrence instants would reject a valid paired closure.
        return RunOutcomeVerification(
            RunVerificationStatus.ACCEPTED,
            condition,
            event.caseStreamVersion,
            evidence = history.selected(selected),
            acceptance =
                RunProofAcceptance(event.sequence, event.acceptedAt, stored.recordedAt, event.caseStreamVersion + 1),
        )
    }
}

private fun List<CaseEvent>.selected(evidence: ResolutionOutcomeEvidence): RunVerificationEvidence {
    val fact = getOrNull((evidence.factStreamVersion - 1).toInt()) as? AccountAccessStateBound
    val source = getOrNull((evidence.observationStreamVersion - 1).toInt()) as? ObservationRecorded
    check(
        fact != null && source != null && fact.factId == evidence.factId.value &&
            source.observationId == evidence.observationId.value && fact.observationId == source.observationId,
    )
    check(
        source.observationOriginType == ObservationOriginType.CONNECTOR &&
            source.observationReference == fact.accountReference,
    )
    return RunVerificationEvidence(evidence, fact.accountReference, source)
}
