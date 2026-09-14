package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionSnapshot
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CapabilityInvocationServiceTest {
    @Test
    fun `invokes outside transactions with the consumption identity as idempotency key`() {
        val transactions = RecordingTransactionRunner()
        val consumption = consumption()
        val receiptRepository = InMemoryReceiptRepository()
        val observedKeys = mutableListOf<UUID>()
        val service =
            service(
                transactions,
                receiptRepository,
                CapabilityConnectorGateway { command ->
                    check(!transactions.active) { "connector invoked inside database transaction" }
                    observedKeys += command.idempotencyKey
                    connectorResult(command.idempotencyKey)
                },
                consumption,
            )

        val execution = service.invoke(TENANT_ID, CONSUMPTION_ID)

        assertThat(execution.created).isTrue()
        assertThat(observedKeys).containsExactly(CONSUMPTION_ID)
        assertThat(transactions.invocations).isEqualTo(2)
        assertThat(execution.storedReceipt.receipt.idempotencyKey).isEqualTo(CONSUMPTION_ID)
    }

    @Test
    fun `replays a durable receipt without invoking the connector`() {
        val transactions = RecordingTransactionRunner()
        val consumption = consumption()
        val receiptRepository = InMemoryReceiptRepository()
        val connectorCalls = mutableListOf<UUID>()
        val service =
            service(
                transactions,
                receiptRepository,
                CapabilityConnectorGateway { command ->
                    connectorCalls += command.idempotencyKey
                    connectorResult(command.idempotencyKey)
                },
                consumption,
            )
        val first = service.invoke(TENANT_ID, CONSUMPTION_ID)

        val replay = service.invoke(TENANT_ID, CONSUMPTION_ID)

        assertThat(replay.created).isFalse()
        assertThat(replay.storedReceipt).isEqualTo(first.storedReceipt)
        assertThat(connectorCalls).containsExactly(CONSUMPTION_ID)
        assertThat(transactions.invocations).isEqualTo(3)
    }

    private fun service(
        transactions: RecordingTransactionRunner,
        receipts: InMemoryReceiptRepository,
        connector: CapabilityConnectorGateway,
        consumption: CapabilityAuthorizationConsumption,
    ) = CapabilityInvocationService(
        consumptions =
            object : CapabilityAuthorizationConsumptionRepository {
                override fun find(
                    tenantId: TenantId,
                    consumptionId: CapabilityAuthorizationConsumptionId,
                ): StoredCapabilityAuthorizationConsumption? =
                    if (tenantId.value == TENANT_ID && consumptionId.value == CONSUMPTION_ID) {
                        StoredCapabilityAuthorizationConsumption(consumption, CONSUMED_AT)
                    } else {
                        null
                    }

                override fun findIdByGrant(
                    tenantId: TenantId,
                    grantId: CapabilityAuthorizationGrantId,
                ) = null

                override fun create(
                    tenantId: TenantId,
                    consumption: CapabilityAuthorizationConsumption,
                ) = error("not used")
            },
        receipts = receipts,
        connector = connector,
        transactionRunner = transactions,
        clock = Clock.fixed(COMPLETED_AT, ZoneOffset.UTC),
    )

    private fun consumption(): CapabilityAuthorizationConsumption =
        CapabilityAuthorizationConsumption.rehydrate(
            CapabilityAuthorizationConsumptionSnapshot(
                id = CapabilityAuthorizationConsumptionId(CONSUMPTION_ID),
                authorizationGrantId = CapabilityAuthorizationGrantId(GRANT_ID),
                runId = ResolutionRunId(RUN_ID),
                caseId = CaseId(CASE_ID),
                policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                stepId = ResolutionStepId.of("unlock-account"),
                capability = CapabilityName.of("identity.account.unlock"),
                connector = ConnectorName.of("identity-stub"),
                grantAuthorizedAt = Instant.parse("2026-09-14T10:00:00Z"),
                grantExpiresAt = Instant.parse("2026-09-14T10:15:00Z"),
                consumedAt = CONSUMED_AT,
            ),
        )

    private fun connectorResult(idempotencyKey: UUID) =
        CapabilityInvocationResult(
            CapabilityInvocationOutcome.SUCCEEDED,
            ProviderOperationReference.of("identity-stub/operations/$idempotencyKey"),
        )

    companion object {
        private val TENANT_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val CONSUMPTION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val GRANT_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val RUN_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        private val CASE_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        private val CONSUMED_AT: Instant = Instant.parse("2026-09-14T10:00:30Z")
        private val COMPLETED_AT: Instant = Instant.parse("2026-09-14T10:01:00Z")
    }
}

private class RecordingTransactionRunner : TransactionRunner {
    var active: Boolean = false
        private set
    var invocations: Int = 0
        private set

    override fun <T : Any> required(block: () -> T): T {
        check(!active) { "test transaction runner does not support nesting" }
        invocations += 1
        active = true
        return try {
            block()
        } finally {
            active = false
        }
    }
}

private class InMemoryReceiptRepository : CapabilityInvocationReceiptRepository {
    private var stored: StoredCapabilityInvocationReceipt? = null

    override fun find(
        tenantId: TenantId,
        consumptionId: CapabilityAuthorizationConsumptionId,
    ): StoredCapabilityInvocationReceipt? = stored

    override fun createOrFind(
        tenantId: TenantId,
        receipt: CapabilityInvocationReceipt,
    ): CapabilityInvocationExecution {
        stored?.let { return CapabilityInvocationExecution(it, created = false) }
        val created = StoredCapabilityInvocationReceipt(receipt, receipt.completedAt)
        stored = created
        return CapabilityInvocationExecution(created, created = true)
    }
}
