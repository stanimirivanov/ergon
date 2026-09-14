package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CapabilityInvocationReceiptTest {
    @Test
    fun `copies authorization scope and derives a stable idempotency key`() {
        val consumption = consumption()

        val receipt =
            CapabilityInvocationReceipt.complete(
                consumption = consumption,
                result =
                    CapabilityInvocationResult(
                        CapabilityInvocationOutcome.SUCCEEDED,
                        ProviderOperationReference.of("operations/unlock-42"),
                    ),
                completedAt = Instant.parse("2026-09-14T10:01:00Z"),
            )

        assertThat(receipt.authorizationConsumptionId).isEqualTo(consumption.id)
        assertThat(receipt.authorizationGrantId).isEqualTo(consumption.authorizationGrantId)
        assertThat(receipt.runId).isEqualTo(consumption.runId)
        assertThat(receipt.caseId).isEqualTo(consumption.caseId)
        assertThat(receipt.policyRevision).isEqualTo(consumption.policyRevision)
        assertThat(receipt.stepId).isEqualTo(consumption.stepId)
        assertThat(receipt.capability).isEqualTo(consumption.capability)
        assertThat(receipt.connector).isEqualTo(consumption.connector)
        assertThat(receipt.idempotencyKey).isEqualTo(consumption.id.value)
    }

    @Test
    fun `rejects completion before authorization consumption`() {
        assertThatThrownBy {
            CapabilityInvocationReceipt.complete(
                consumption = consumption(),
                result =
                    CapabilityInvocationResult(
                        CapabilityInvocationOutcome.SUCCEEDED,
                        ProviderOperationReference.of("operations/unlock-42"),
                    ),
                completedAt = Instant.parse("2026-09-14T09:59:59Z"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("capability invocation completion predates authorization consumption")
    }

    @Test
    fun `permits completion after the source grant expires`() {
        val receipt =
            CapabilityInvocationReceipt.complete(
                consumption = consumption(),
                result =
                    CapabilityInvocationResult(
                        CapabilityInvocationOutcome.SUCCEEDED,
                        ProviderOperationReference.of("operations/unlock-42"),
                    ),
                completedAt = Instant.parse("2026-09-14T10:16:00Z"),
            )

        assertThat(receipt.completedAt).isAfter(Instant.parse("2026-09-14T10:15:00Z"))
    }

    private fun consumption(): CapabilityAuthorizationConsumption =
        CapabilityAuthorizationConsumption.rehydrate(
            CapabilityAuthorizationConsumptionSnapshot(
                id = CapabilityAuthorizationConsumptionId(CONSUMPTION_ID),
                authorizationGrantId = CapabilityAuthorizationGrantId(UUID.randomUUID()),
                runId = ResolutionRunId(RUN_ID),
                caseId = CaseId(CASE_ID),
                policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                stepId = ResolutionStepId.of("unlock-account"),
                capability = CapabilityName.of("identity.account.unlock"),
                connector = ConnectorName.of("identity-stub"),
                grantAuthorizedAt = Instant.parse("2026-09-14T10:00:00Z"),
                grantExpiresAt = Instant.parse("2026-09-14T10:15:00Z"),
                consumedAt = Instant.parse("2026-09-14T10:00:30Z"),
            ),
        )

    companion object {
        private val CONSUMPTION_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val RUN_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val CASE_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    }
}
