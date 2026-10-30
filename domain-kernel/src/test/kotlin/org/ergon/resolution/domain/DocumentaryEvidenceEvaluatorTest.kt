package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DocumentaryEvidenceEvaluatorTest {
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val reference = DocumentaryBundleRequirement(UUID.randomUUID(), "a".repeat(64))
    private val pin = DocumentaryBundlePin(reference, 2, at)
    private val condition = FactCondition(ContractFactType.of("account.access.state"), ContractFactValue.of("LOCKED"))

    @Test
    fun `validity uses exact half open attachment time and unknown validity never qualifies`() {
        assertThat(assess(at, at.plusNanos(1000)).status).isEqualTo(DocumentaryRequirementStatus.SATISFIED)
        assertThat(assess(at.minusNanos(1000), at).reason).isEqualTo(DocumentaryUnsuitability.OUTSIDE_VALIDITY)
        assertThat(assess(at.plusNanos(1000), at.plusSeconds(1)).reason)
            .isEqualTo(DocumentaryUnsuitability.OUTSIDE_VALIDITY)
        assertThat(assess(null, null).reason).isEqualTo(DocumentaryUnsuitability.UNKNOWN_VALIDITY)
    }

    @Test
    fun `procedure only historical material qualifies without a claim interval and later time is irrelevant`() {
        val material = DocumentaryBundleMaterial(reference, at.minusSeconds(10), emptyList())
        val attached = PinnedDocumentaryBundle(pin, material)
        assertThat(DocumentaryEvidenceEvaluator.assess(listOf(reference), listOf(attached)).single().status)
            .isEqualTo(DocumentaryRequirementStatus.SATISFIED)
        val historic = PinnedDocumentaryBundle(pin.copy(attachedAt = at.minusSeconds(5)), material)
        assertThat(DocumentaryEvidenceEvaluator.assess(listOf(reference), listOf(historic)).single().status)
            .isEqualTo(DocumentaryRequirementStatus.SATISFIED)
    }

    @Test
    fun `exact digest and publication cutoff are required and absent pins remain missing`() {
        assertThat(DocumentaryEvidenceEvaluator.assess(listOf(reference), emptyList()).single().status)
            .isEqualTo(DocumentaryRequirementStatus.MISSING)
        val material = DocumentaryBundleMaterial(reference, at.plusNanos(1000), emptyList())
        val attached = PinnedDocumentaryBundle(pin, material)
        assertThat(DocumentaryEvidenceEvaluator.assess(listOf(reference), listOf(attached)).single().reason)
            .isEqualTo(DocumentaryUnsuitability.MANIFEST_AFTER_ATTACHMENT)
        val mismatched = listOf(reference.copy(sha256 = "b".repeat(64)))
        assertThat(DocumentaryEvidenceEvaluator.assess(mismatched, listOf(attached)))
            .extracting("reason")
            .containsExactly(DocumentaryUnsuitability.DIGEST_MISMATCH)
    }

    @Test
    fun `documentary satisfaction cannot supply missing operational facts or bypass applicability`() {
        val contract = contract().requiringDocumentaryEvidence(listOf(reference))
        val attached = PinnedDocumentaryBundle(pin, DocumentaryBundleMaterial(reference, at, emptyList()))
        assertThat(ResolutionReadinessEvaluator.evaluate(contract, ResolutionEvidence.of(emptyMap()), listOf(attached)))
            .isInstanceOf(ResolutionReadiness.MissingEvidence::class.java)
        val evidence = ResolutionEvidence.of(mapOf(condition.fact to condition.expectedValue))
        assertThat(ResolutionReadinessEvaluator.evaluate(contract, evidence, listOf(attached)))
            .isInstanceOf(ResolutionReadiness.Ready::class.java)
        assertThat(ResolutionReadinessEvaluator.evaluate(contract, evidence, emptyList()))
            .isInstanceOf(ResolutionReadiness.DocumentaryEvidenceUnavailable::class.java)
    }

    private fun assess(
        from: Instant?,
        until: Instant?,
    ) = DocumentaryEvidenceEvaluator
        .assess(
            listOf(reference),
            listOf(
                PinnedDocumentaryBundle(
                    pin,
                    DocumentaryBundleMaterial(reference, at, listOf(DocumentaryClaimValidity(from, until))),
                ),
            ),
        ).single()

    private fun contract() =
        ResolutionContract.define(
            ResolutionContractIdentity(ResolutionContractKey.of("documentary"), ResolutionContractRevision.of(1)),
            condition,
            listOf(condition.fact),
            listOf(
                ResolutionStep(
                    ResolutionStepId.of("unlock"),
                    CapabilityName.of("identity.account.unlock"),
                    StepRisk.HIGH,
                    ApprovalRequirement.REQUESTER,
                ),
            ),
            condition,
        )
}
