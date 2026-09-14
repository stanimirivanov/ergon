package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class CapabilityAuthorizationConsumptionTest {
    @Test
    fun `consumes exact grant scope through matching connector route`() {
        val consumption =
            CapabilityAuthorizationConsumption.consume(
                id = CONSUMPTION_ID,
                basis = CapabilityAuthorizationConsumptionBasis(GRANT, ROUTE),
                consumedAt = NOW,
            )

        assertThat(consumption.authorizationGrantId).isEqualTo(GRANT.id)
        assertThat(consumption.runId).isEqualTo(GRANT.runId)
        assertThat(consumption.caseId).isEqualTo(GRANT.caseId)
        assertThat(consumption.policyRevision).isEqualTo(GRANT.policyRevision)
        assertThat(consumption.stepId).isEqualTo(GRANT.stepId)
        assertThat(consumption.capability).isEqualTo(GRANT.capability)
        assertThat(consumption.connector).isEqualTo(ROUTE.connector)
        assertThat(consumption.grantExpiresAt).isEqualTo(GRANT.expiresAt)
    }

    @Test
    fun `rejects consumption before authorization and at exclusive expiry`() {
        assertThatThrownBy {
            CapabilityAuthorizationConsumption.consume(
                CONSUMPTION_ID,
                CapabilityAuthorizationConsumptionBasis(GRANT, ROUTE),
                GRANT.authorizedAt.minusNanos(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authorization consumption predates its grant")

        assertThatThrownBy {
            CapabilityAuthorizationConsumption.consume(
                CONSUMPTION_ID,
                CapabilityAuthorizationConsumptionBasis(GRANT, ROUTE),
                GRANT.expiresAt,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authorization grant is expired")
    }

    @Test
    fun `rejects connector route for another capability`() {
        val otherRoute = ROUTE.copy(capability = CapabilityName.of("identity.password.reset"))

        assertThatThrownBy {
            CapabilityAuthorizationConsumption.consume(
                CONSUMPTION_ID,
                CapabilityAuthorizationConsumptionBasis(GRANT, otherRoute),
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("capability route does not provide the authorized capability")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-14T16:00:00Z")
        val CONSUMPTION_ID = CapabilityAuthorizationConsumptionId(UUID.randomUUID())
        val GRANT =
            CapabilityAuthorizationGrant.rehydrate(
                CapabilityAuthorizationGrantSnapshot(
                    id = CapabilityAuthorizationGrantId(UUID.randomUUID()),
                    approvalDecisionId = ApprovalDecisionId(UUID.randomUUID()),
                    approvalRequestId = ApprovalRequestId(UUID.randomUUID()),
                    runId = ResolutionRunId(UUID.randomUUID()),
                    caseId = CaseId(UUID.randomUUID()),
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                    stepId = ResolutionStepId.of("unlock-account"),
                    capability = CapabilityName.of("identity.account.unlock"),
                    authorizedAt = NOW.minus(1, ChronoUnit.MINUTES),
                    expiresAt = NOW.plus(5, ChronoUnit.MINUTES),
                ),
            )
        val ROUTE = CapabilityRoute(GRANT.capability, ConnectorName.of("identity-stub"))
    }
}
