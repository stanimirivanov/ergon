package org.ergon.evaluation.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationReceiptSnapshot
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ScenarioAssertionsTest {
    private val capability = CapabilityName.of("identity.account.unlock")
    private val other = CapabilityName.of("identity.other")
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val condition = FactCondition(ContractFactType.of("account.access.state"), ContractFactValue.of("ACTIVE"))
    private val evidence =
        ResolutionOutcomeEvidence(
            FactId(UUID(0, 1)),
            condition.fact,
            condition.expectedValue,
            ObservationId(UUID(0, 2)),
            5,
            6,
            at,
            at,
        )
    private val accepted = ResolutionOutcomeProofAssessment.Accepted(condition, 6, evidence)
    private val receiptSnapshot =
        CapabilityInvocationReceiptSnapshot(
            CapabilityAuthorizationConsumptionId(UUID(0, 3)),
            CapabilityAuthorizationGrantId(UUID(0, 4)),
            ResolutionRunId(UUID(0, 5)),
            CaseId(UUID(0, 6)),
            ResolutionPolicyRevision.of("ergon/access/v1"),
            ResolutionStepId.of("unlock"),
            capability,
            ConnectorName.of("simulation.identity"),
            UUID(0, 3),
            CapabilityInvocationOutcome.SUCCEEDED,
            ProviderOperationReference.of("operation-1"),
            at,
            at,
        )
    private val proof = ScenarioProof(accepted, CapabilityInvocationReceipt.rehydrate(receiptSnapshot), 4)
    private val bundle = DocumentaryBundleRequirement(UUID(0, 7), "a".repeat(64))
    private val grounding = ScenarioGrounding(listOf(UUID(0, 8)), listOf(bundle))
    private val policy = StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.RESOLVER)
    private val trace =
        ScenarioAssertionTrace(
            available(SimulationExpectedOutcome.VERIFIED),
            available(ObservedInvocations(listOf(capability))),
            available(policy),
            available(grounding),
            available(proof),
            available(null),
        )
    private val checks =
        listOf(
            ScenarioCheck.Outcome(SimulationExpectedOutcome.VERIFIED),
            ScenarioCheck.AllowedInvocations(setOf(capability)),
            ScenarioCheck.ExactInvocations(listOf(capability)),
            ScenarioCheck.Policy(policy),
            ScenarioCheck.Grounding(grounding),
            ScenarioCheck.AcceptedProof(condition),
            ScenarioCheck.NoUnprovedClosure(condition),
            ScenarioCheck.Escalation(null),
        )

    private fun <T> available(value: T): ScenarioObservation<T> = ScenarioObservation.Available(value)

    private fun evaluate(
        check: ScenarioCheck,
        observed: ScenarioAssertionTrace = trace,
    ): AssertionResult =
        ScenarioAssertionEvaluator
            .evaluate(
                ScenarioAssertions(listOf(ScenarioAssertion("check", true, check))),
                observed,
            ).results
            .single()

    @Test
    fun `independent checks retain ordered stable identities and pass matching observations`() {
        val assertions = checks.mapIndexed { index, check -> ScenarioAssertion("check-$index", true, check) }
        val result = ScenarioAssertionEvaluator.evaluate(ScenarioAssertions(assertions), trace)
        assertThat(result.results.map { it.id }).containsExactlyElementsOf(assertions.map { it.id })
        assertThat(result.results.map { it.status }).containsOnly(AssertionStatus.PASS)
        assertThat(result.results.map { it.explanation }).containsOnly(AssertionExplanation.MATCH)
        assertThat(result.requiredChecksPassed).isTrue()
        assertThat(ScenarioAssertionEvaluator.evaluate(ScenarioAssertions(assertions), trace).results)
            .isEqualTo(result.results)
    }

    @Test
    fun `corrupted outcomes capabilities policy and grounding fail explicit expectations`() {
        val weakPolicy = StepPolicyDecision.Requirements(StepRisk.LOW, ApprovalRequirement.NONE)
        val wrongBundle = bundle.copy(sha256 = "b".repeat(64))
        val wrongGrounding = ScenarioGrounding(grounding.factIds, listOf(wrongBundle))
        val variants =
            listOf(
                checks[0] to trace.copy(outcome = available(SimulationExpectedOutcome.FAILED)),
                checks[1] to trace.copy(invocations = available(ObservedInvocations(listOf(other)))),
                checks[2] to trace.copy(invocations = available(ObservedInvocations(emptyList()))),
                checks[3] to trace.copy(policy = available(weakPolicy)),
                checks[4] to trace.copy(grounding = available(ScenarioGrounding(listOf(UUID(0, 9)), listOf(bundle)))),
                checks[4] to trace.copy(grounding = available(ScenarioGrounding(grounding.factIds, emptyList()))),
                checks[4] to
                    trace.copy(grounding = available(wrongGrounding)),
            )
        variants.forEach { (check, corrupted) ->
            assertThat(evaluate(check, corrupted).status).isEqualTo(AssertionStatus.FAIL)
        }
    }

    @Test
    fun `forbidden calls fail and retry multiplicity is not discarded`() {
        val forbidden = ScenarioCheck.AllowedInvocations(emptySet())
        assertThat(evaluate(forbidden).status).isEqualTo(AssertionStatus.FAIL)
        assertThat(evaluate(forbidden, trace.copy(invocations = available(ObservedInvocations(emptyList())))).status)
            .isEqualTo(AssertionStatus.PASS)
        val retry = trace.copy(invocations = available(ObservedInvocations(listOf(capability, capability))))
        assertThat(evaluate(ScenarioCheck.ExactInvocations(listOf(capability, capability)), retry).status)
            .isEqualTo(AssertionStatus.PASS)
        assertThat(evaluate(ScenarioCheck.ExactInvocations(listOf(capability)), retry).status)
            .isEqualTo(AssertionStatus.FAIL)
    }

    @Test
    fun `missing and unsupported observations never count as required passes`() {
        val entries = checks.mapIndexed { i, check -> ScenarioAssertion("check-$i", true, check) }
        val assertions = ScenarioAssertions(entries)
        val missing = ScenarioAssertionEvaluator.evaluate(assertions, ScenarioAssertionTrace())
        assertThat(missing.results.map { it.status }).containsOnly(AssertionStatus.ERROR)
        assertThat(missing.results.map { it.explanation }).containsOnly(AssertionExplanation.MISSING_OBSERVATION)
        val unsupported =
            ScenarioAssertionTrace(
                ScenarioObservation.Unsupported,
                ScenarioObservation.Unsupported,
                ScenarioObservation.Unsupported,
                ScenarioObservation.Unsupported,
                ScenarioObservation.Unsupported,
                ScenarioObservation.Unsupported,
            )
        val result = ScenarioAssertionEvaluator.evaluate(assertions, unsupported)
        assertThat(result.results.map { it.status }).containsOnly(AssertionStatus.UNSUPPORTED)
        assertThat(result.requiredChecksPassed).isFalse()
        assertThat(missing.requiredChecksPassed).isFalse()
        val broken = unsupported.copy(escalation = ScenarioObservation.Error)
        assertThat(evaluate(ScenarioCheck.Escalation(null), broken).status).isEqualTo(AssertionStatus.ERROR)
    }

    @Test
    fun `optional failures remain visible and required unknown checks fail closed`() {
        val pass = ScenarioAssertion("outcome", true, ScenarioCheck.Outcome(SimulationExpectedOutcome.VERIFIED))
        val optional = ScenarioAssertion("future", false, ScenarioCheck.Unsupported("new-check"))
        val first = ScenarioAssertionEvaluator.evaluate(ScenarioAssertions(listOf(pass, optional)), trace)
        assertThat(first.requiredChecksPassed).isTrue()
        assertThat(first.results.last().status).isEqualTo(AssertionStatus.UNSUPPORTED)
        assertThat(first.results.last().explanation).isEqualTo(AssertionExplanation.UNSUPPORTED_ASSERTION)
        val requiredSet = ScenarioAssertions(listOf(pass, optional.copy(required = true)))
        val required = ScenarioAssertionEvaluator.evaluate(requiredSet, trace)
        assertThat(required.requiredChecksPassed).isFalse()
    }

    @Test
    fun `accepted proof must have successful receipt matching condition and post action provenance`() {
        val failedSnapshot = receiptSnapshot.copy(outcome = CapabilityInvocationOutcome.FAILED)
        val failedReceipt = CapabilityInvocationReceipt.rehydrate(failedSnapshot)
        val stale = evidence.copy(observedAt = at.minusSeconds(1))
        val preRun = evidence.copy(observationStreamVersion = 3)
        val invalid =
            listOf(
                proof.copy(receipt = null),
                proof.copy(receipt = failedReceipt),
                proof.copy(assessment = accepted.copy(evidence = stale)),
                proof.copy(assessment = accepted.copy(evidence = preRun)),
                proof.copy(assessment = accepted.copy(caseStreamVersion = 5)),
            )
        invalid.forEach { value ->
            val result = evaluate(ScenarioCheck.AcceptedProof(condition), trace.copy(proof = available(value)))
            assertThat(result.status).isEqualTo(AssertionStatus.FAIL)
            assertThat(result.explanation).isEqualTo(AssertionExplanation.INVALID_PROOF_PROVENANCE)
        }
        val wrongCondition = condition.copy(expectedValue = ContractFactValue.of("LOCKED"))
        assertThat(evaluate(ScenarioCheck.AcceptedProof(wrongCondition)).status).isEqualTo(AssertionStatus.FAIL)
    }

    @Test
    fun `verified closure without accepted proof fails while unobserved closure stays unsupported`() {
        val check = ScenarioCheck.NoUnprovedClosure(condition)
        val noProof = trace.copy(proof = available(proof.copy(assessment = null)))
        assertThat(evaluate(check, noProof).status).isEqualTo(AssertionStatus.FAIL)
        assertThat(evaluate(check, noProof).explanation).isEqualTo(AssertionExplanation.PROOF_NOT_ACCEPTED)
        assertThat(evaluate(check, trace.copy(proof = ScenarioObservation.Unsupported)).status)
            .isEqualTo(AssertionStatus.UNSUPPORTED)
        assertThat(evaluate(check, trace.copy(outcome = ScenarioObservation.Unsupported)).status)
            .isEqualTo(AssertionStatus.UNSUPPORTED)
        assertThat(evaluate(check, noProof.copy(outcome = available(SimulationExpectedOutcome.FAILED))).status)
            .isEqualTo(AssertionStatus.PASS)
    }

    @Test
    fun `escalation requires both expected reason and consistent terminal observation`() {
        val reason = org.ergon.resolution.domain.ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED
        val escalated =
            trace.copy(
                outcome = available(SimulationExpectedOutcome.ESCALATED),
                escalation = available(reason),
            )
        assertThat(evaluate(ScenarioCheck.Escalation(reason), escalated).status).isEqualTo(AssertionStatus.PASS)
        assertThat(evaluate(ScenarioCheck.Escalation(null), escalated).status).isEqualTo(AssertionStatus.FAIL)
        assertThat(evaluate(ScenarioCheck.Escalation(reason), trace.copy(escalation = available(reason))).status)
            .isEqualTo(AssertionStatus.FAIL)
        assertThat(evaluate(ScenarioCheck.Escalation(null), escalated.copy(escalation = available(null))).status)
            .isEqualTo(AssertionStatus.FAIL)
    }

    @Test
    fun `empty optional only duplicate invalid and oversized assertion catalogs are rejected`() {
        val valid = ScenarioAssertion("check", true, checks.first())
        assertThatIllegalArgumentException().isThrownBy { ScenarioAssertions(emptyList()) }
        assertThatIllegalArgumentException().isThrownBy { ScenarioAssertions(listOf(valid.copy(required = false))) }
        assertThatIllegalArgumentException().isThrownBy { ScenarioAssertions(listOf(valid, valid)) }
        assertThatIllegalArgumentException().isThrownBy { valid.copy(id = "bad id") }
        assertThatIllegalArgumentException().isThrownBy {
            ScenarioAssertions(List(101) { valid.copy(id = "check-$it") })
        }
    }

    @Test
    fun `caller mutation cannot change expectations observations or result lists`() {
        val calls = mutableListOf(capability)
        val check = ScenarioCheck.ExactInvocations(calls)
        val observed = ObservedInvocations(calls)
        val entries = mutableListOf(ScenarioAssertion("calls", true, check))
        val assertions = ScenarioAssertions(entries)
        calls.clear()
        entries.clear()
        val result = ScenarioAssertionEvaluator.evaluate(assertions, trace.copy(invocations = available(observed)))
        assertThat(result.requiredChecksPassed).isTrue()
        assertThat(result.results).hasSize(1)
        assertThatThrownBy { (result.results as MutableList).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
        val facts = mutableListOf(UUID(0, 1))
        val frozen = ScenarioGrounding(facts, emptyList())
        facts.clear()
        assertThat(frozen.factIds).hasSize(1)
    }
}
