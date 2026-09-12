package org.ergon.controlplane.contracts.adapter.inbound.document

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.contracts.application.InvalidResolutionContractDocumentException
import org.junit.jupiter.api.Test

class SnakeYamlResolutionContractDocumentDecoderTest {
    private val decoder = SnakeYamlResolutionContractDocumentDecoder()

    @Test
    fun `decodes strict access restoration contract`() {
        val contract = decoder.decode(VALID_CONTRACT)

        assertThat(contract.key.value).isEqualTo("restore-workspace-access")
        assertThat(contract.revision.value).isEqualTo(1)
        assertThat(contract.applicability.fact.value).isEqualTo("account.access.state")
        assertThat(contract.requiredEvidence.map { it.value }).containsExactly("account.access.state")
        assertThat(contract.steps.single().risk).isEqualTo(StepRisk.HIGH)
        assertThat(contract.steps.single().approval).isEqualTo(ApprovalRequirement.REQUESTER)
        assertThat(contract.outcomeProof.expectedValue.value).isEqualTo("ACTIVE")
    }

    @Test
    fun `rejects unknown and duplicate fields`() {
        assertViolation(
            VALID_CONTRACT.replace("revision: 1", "revision: 1\ndescription: hidden behavior"),
            "$.description",
        )
        assertViolation(
            VALID_CONTRACT.replace("revision: 1", "revision: 1\nrevision: 2"),
            "$",
        )
    }

    @Test
    fun `rejects malformed and unsafe contracts`() {
        assertViolation(VALID_CONTRACT.replace("approval: REQUESTER", "approval: NONE"), "$.steps[0]")
        assertViolation(VALID_CONTRACT.replace("revision: 1", "revision: first"), "$.revision")
        assertViolation(
            VALID_CONTRACT
                .replace("applicability:\n", "applicability: &condition\n")
                .replace(
                    "outcomeProof:\n  fact: account.access.state\n  equals: ACTIVE",
                    "outcomeProof: *condition",
                ),
            "$",
        )
        assertViolation("schema: [", "$")
        assertViolation("x".repeat(50_001), "$")
    }

    private fun assertViolation(
        document: String,
        path: String,
    ) {
        assertThatThrownBy { decoder.decode(document) }
            .isInstanceOfSatisfying(InvalidResolutionContractDocumentException::class.java) { exception ->
                assertThat(exception.violations.single().path).isEqualTo(path)
            }
    }

    private companion object {
        val VALID_CONTRACT =
            requireNotNull(
                SnakeYamlResolutionContractDocumentDecoderTest::class.java
                    .getResource("/contracts/restore-workspace-access.yaml"),
            ).readText()
    }
}
