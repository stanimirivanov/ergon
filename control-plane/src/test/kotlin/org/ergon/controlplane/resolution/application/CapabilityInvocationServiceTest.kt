package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
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

    @ParameterizedTest
    @EnumSource(CapabilityInvocationOutcome::class)
    fun `records terminal timing and replays without resampling`(outcome: CapabilityInvocationOutcome) {
        val transactions = RecordingTransactionRunner()
        val receipts = InMemoryReceiptRepository()
        val ticker = Mockito.mock(CapabilityInvocationTicker::class.java)
        Mockito
            .`when`(ticker.readNanoseconds())
            .thenAnswer {
                check(!transactions.active)
                -500L
            }.thenAnswer {
                check(!transactions.active)
                12_345_178L
            }
        val clock = Mockito.mock(Clock::class.java)
        val start = COMPLETED_AT.plusNanos(123_456_789)
        // The wall clock moves backwards, but remains later than consumption.
        Mockito.`when`(clock.instant()).thenReturn(start, COMPLETED_AT.plusNanos(12_345_678))
        val service =
            service(
                transactions,
                receipts,
                CapabilityConnectorGateway { command ->
                    check(!transactions.active)
                    connectorResult(command.idempotencyKey).copy(outcome = outcome)
                },
                consumption(),
                ticker,
                clock,
            )

        val first = service.invoke(TENANT_ID, CONSUMPTION_ID)
        val replay = service.invoke(TENANT_ID, CONSUMPTION_ID)

        assertThat(first.storedReceipt.receipt.outcome).isEqualTo(outcome)
        val timing = requireNotNull(first.storedReceipt.receipt.timing)
        assertThat(timing.elapsedNanoseconds).isEqualTo(12_345_678L)
        assertThat(timing.startedAt).isEqualTo(COMPLETED_AT.plusNanos(123_456_000))
        assertThat(first.storedReceipt.receipt.completedAt).isEqualTo(COMPLETED_AT.plusNanos(12_345_000))
        assertThat(replay.storedReceipt).isEqualTo(first.storedReceipt)
        Mockito.verify(ticker, Mockito.times(2)).readNanoseconds()
        Mockito.verify(clock, Mockito.times(2)).instant()
    }

    @Test
    fun `monotonic readings may wrap without changing measured duration`() {
        val ticker = Mockito.mock(CapabilityInvocationTicker::class.java)
        Mockito.`when`(ticker.readNanoseconds()).thenReturn(Long.MAX_VALUE - 5, Long.MIN_VALUE + 4)
        val service =
            service(
                RecordingTransactionRunner(),
                InMemoryReceiptRepository(),
                CapabilityConnectorGateway { connectorResult(it.idempotencyKey) },
                consumption(),
                ticker,
            )

        assertThat(
            service
                .invoke(TENANT_ID, CONSUMPTION_ID)
                .storedReceipt.receipt.timing
                ?.elapsedNanoseconds,
        ).isEqualTo(10L)
    }

    @Test
    fun `unknown connector failure creates neither a receipt nor fabricated timing`() {
        val receipts = InMemoryReceiptRepository()
        val failure = IllegalStateException("synthetic uncertain provider result")
        val service =
            service(
                RecordingTransactionRunner(),
                receipts,
                CapabilityConnectorGateway { throw failure },
                consumption(),
            )

        assertThatThrownBy { service.invoke(TENANT_ID, CONSUMPTION_ID) }.isSameAs(failure)
        assertThat(receipts.find(TenantId(TENANT_ID), CapabilityAuthorizationConsumptionId(CONSUMPTION_ID))).isNull()
    }

    @Test
    fun `legacy replay does not backfill or invoke a timing source`() {
        val receipts = InMemoryReceiptRepository()
        val original =
            receipts.createOrFind(
                TenantId(TENANT_ID),
                CapabilityInvocationReceipt.complete(consumption(), connectorResult(CONSUMPTION_ID), COMPLETED_AT),
            )
        val service =
            service(
                RecordingTransactionRunner(),
                receipts,
                CapabilityConnectorGateway { error("replay must not invoke") },
                consumption(),
                CapabilityInvocationTicker { error("replay must not measure") },
            )

        val replay = service.invoke(TENANT_ID, CONSUMPTION_ID)
        assertThat(replay.created).isFalse()
        assertThat(replay.storedReceipt).isEqualTo(original.storedReceipt)
        assertThat(replay.storedReceipt.receipt.timing).isNull()
    }

    @Suppress("LongParameterList") // Independent wall/monotonic sources and execution fakes remain explicit in tests.
    private fun service(
        transactions: RecordingTransactionRunner,
        receipts: InMemoryReceiptRepository,
        connector: CapabilityConnectorGateway,
        consumption: CapabilityAuthorizationConsumption,
        ticker: CapabilityInvocationTicker = CapabilityInvocationTicker { 123L },
        clock: Clock = Clock.fixed(COMPLETED_AT, ZoneOffset.UTC),
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
        clock = clock,
        ticker = ticker,
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

    override fun findByRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
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
