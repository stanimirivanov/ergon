package org.ergon.controlplane.resolution.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.CapabilityInvocationExecution
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionSnapshot
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.ergon.resolution.domain.CapabilityInvocationTiming
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresInvocationTimingIntegrationTest(
    @Autowired private val receipts: CapabilityInvocationReceiptRepository,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val dataSource: DataSource,
) {
    @Test
    fun `receipt and exact timing round trip with tenant isolation and immutable measurement`() {
        val tenant = TenantId(UUID.randomUUID())
        val consumption = consumption()
        seedConsumption(dataSource, tenant, consumption)
        val receipt = receipt(consumption, 12_345_678)

        val created = transactions.required { receipts.createOrFind(tenant, receipt) }
        val read = receipts.find(tenant, consumption.id)
        assertThat(read).isEqualTo(created.storedReceipt)
        assertThat(read?.receipt?.timing?.elapsedNanoseconds).isEqualTo(12_345_678L)
        assertThat(receipts.findByRun(tenant, consumption.runId)).isEqualTo(read)
        assertThat(receipts.find(TenantId(UUID.randomUUID()), consumption.id)).isNull()
        assertThat(receipts.findByRun(TenantId(UUID.randomUUID()), consumption.runId)).isNull()

        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    UPDATE capability_invocation_receipts
                    SET invocation_elapsed_nanoseconds = 0
                    WHERE tenant_id = :tenant AND authorization_consumption_id = :consumption
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("consumption", consumption.id.value)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(receipts.find(tenant, consumption.id)).isEqualTo(read)
    }

    @Test
    fun `concurrent receipt writers return the same winner timing without merging samples`() {
        val tenant = TenantId(UUID.randomUUID())
        val consumption = consumption()
        seedConsumption(dataSource, tenant, consumption)
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures =
                listOf(100L, 200L).map { elapsed ->
                    executor.submit<CapabilityInvocationExecution> {
                        barrier.await(10, TimeUnit.SECONDS)
                        transactions.required { receipts.createOrFind(tenant, receipt(consumption, elapsed)) }
                    }
                }
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results[0].storedReceipt).isEqualTo(results[1].storedReceipt)
            assertThat(
                results[0]
                    .storedReceipt.receipt.timing
                    ?.elapsedNanoseconds,
            ).isIn(100L, 200L)
            assertThat(receipts.find(tenant, consumption.id)).isEqualTo(results[0].storedReceipt)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `receipt writers wait for the consumption transaction and recover its commit or rollback`(rollback: Boolean) {
        val tenant = TenantId(UUID.randomUUID())
        val consumption = consumption()
        seedConsumption(dataSource, tenant, consumption)
        val workerPid = CompletableFuture<Int>()
        lateinit var pending: Future<CapabilityInvocationExecution>
        Executors.newSingleThreadExecutor().use { executor ->
            val first =
                TransactionTemplate(DataSourceTransactionManager(dataSource)).execute { status ->
                    // This lock permits FK KEY SHARE checks. Only receipt-write
                    // serialization, not INSERT's parent check, must wait here.
                    jdbc
                        .sql(
                            """
                            SELECT authorization_consumption_id
                            FROM capability_authorization_consumptions
                            WHERE tenant_id = :tenant AND authorization_consumption_id = :consumption
                            FOR NO KEY UPDATE
                            """.trimIndent(),
                        ).param("tenant", tenant.value)
                        .param("consumption", consumption.id.value)
                        .query(UUID::class.java)
                        .single()
                    val ownerPid = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()
                    pending =
                        executor.submit<CapabilityInvocationExecution> {
                            transactions.required {
                                workerPid.complete(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                                receipts.createOrFind(tenant, receipt(consumption, 200L))
                            }
                        }
                    val pid = workerPid.get(10, TimeUnit.SECONDS)
                    await().atMost(10, TimeUnit.SECONDS).until {
                        pending.isDone ||
                            jdbc
                                .sql("SELECT :owner = ANY(pg_blocking_pids(:worker))")
                                .param("owner", ownerPid)
                                .param("worker", pid)
                                .query(Boolean::class.java)
                                .single()
                    }
                    assertThat(pending.isDone).describedAs("writer must wait before inserting a receipt").isFalse()
                    receipts.createOrFind(tenant, receipt(consumption, 100L)).also {
                        if (rollback) status.setRollbackOnly()
                    }
                }
            val second = pending.get(20, TimeUnit.SECONDS)
            assertThat(first.created).isTrue()
            assertThat(second.created).isEqualTo(rollback)
            if (!rollback) assertThat(second.storedReceipt).isEqualTo(first.storedReceipt)
            assertThat(
                second.storedReceipt.receipt.timing
                    ?.elapsedNanoseconds,
            ).isEqualTo(if (rollback) 200L else 100L)
            assertThat(receipts.find(tenant, consumption.id)).isEqualTo(second.storedReceipt)
        }
    }

    @Test
    fun `another consumption cannot reuse a provider operation and a failed write leaves the winner unchanged`() {
        val tenant = TenantId(UUID.randomUUID())
        val first = consumption()
        val second = consumption()
        seedConsumption(dataSource, tenant, first)
        seedConsumption(dataSource, tenant, second)
        val winner = transactions.required { receipts.createOrFind(tenant, receipt(first, 100L)) }
        val conflicting =
            CapabilityInvocationReceipt.complete(
                second,
                CapabilityInvocationResult(
                    CapabilityInvocationOutcome.SUCCEEDED,
                    winner.storedReceipt.receipt.providerOperationReference,
                ),
                NOW.plusSeconds(1),
                CapabilityInvocationTiming(NOW, 200L),
            )

        assertThatThrownBy { transactions.required { receipts.createOrFind(tenant, conflicting) } }
            .isInstanceOf(DuplicateKeyException::class.java)
            .hasStackTraceContaining("uq_capability_invocation_receipts_provider_operation")
        assertThat(receipts.find(tenant, first.id)).isEqualTo(winner.storedReceipt)
        assertThat(receipts.find(tenant, second.id)).isNull()
    }

    @Test
    fun `receipt persistence rejects autocommit so serialization lasts until the application commits`() {
        val tenant = TenantId(UUID.randomUUID())
        val consumption = consumption()
        seedConsumption(dataSource, tenant, consumption)

        assertThatThrownBy { receipts.createOrFind(tenant, receipt(consumption, 100L)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("capability invocation receipt persistence requires an active transaction")
        assertThat(receipts.find(tenant, consumption.id)).isNull()
    }

    @Test
    fun `database rejects partial or negative timing and binds measured scope to consumption`() {
        val tenant = TenantId(UUID.randomUUID())
        val consumption = consumption()
        seedConsumption(dataSource, tenant, consumption)
        for ((started, elapsed) in listOf<Pair<Instant?, Long?>>(null to 1L, NOW to null, NOW to -1L)) {
            assertThatThrownBy {
                jdbc
                    .sql(measuredReceiptInsert)
                    .param("tenant", tenant.value)
                    .param("consumption", consumption.id.value)
                    .param("receiptRun", consumption.runId.value)
                    .param("started", started?.atOffset(ZoneOffset.UTC))
                    .param("elapsed", elapsed)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
                .hasStackTraceContaining("ck_capability_invocation_receipts_timing")
        }
        assertThatThrownBy {
            jdbc
                .sql(measuredReceiptInsert)
                .param("tenant", tenant.value)
                .param("consumption", consumption.id.value)
                .param("receiptRun", UUID.randomUUID())
                .param("started", NOW.atOffset(ZoneOffset.UTC))
                .param("elapsed", 1L)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
            .hasStackTraceContaining("fk_capability_invocation_receipts_consumption")
        assertThat(receipts.find(tenant, consumption.id)).isNull()
    }

    @Test
    fun `upgrade preserves historical receipt without synthetic timing and permits older writers`() {
        val schema = "timing_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
        flyway.target("20261009041257").load().migrate()
        val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
        val upgradeDataSource =
            DriverManagerDataSource(
                "${postgres.jdbcUrl}${separator}currentSchema=$schema",
                postgres.username,
                postgres.password,
            )
        val upgradeJdbc = JdbcClient.create(upgradeDataSource)
        val tenant = TenantId(UUID.randomUUID())
        val before = consumption()
        seedConsumption(upgradeDataSource, tenant, before)
        insertLegacy(upgradeJdbc, tenant, before)
        val original = receiptJson(upgradeJdbc, tenant, before)

        Flyway
            .configure()
            .dataSource(upgradeDataSource)
            .defaultSchema(schema)
            .schemas(schema)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        assertThat(receiptJson(upgradeJdbc, tenant, before)).isEqualTo(original)
        val reader = PostgresCapabilityInvocationReceiptRepository(upgradeJdbc)
        assertThat(reader.find(tenant, before.id)?.receipt?.timing).isNull()
        val replay =
            requireNotNull(
                TransactionTemplate(DataSourceTransactionManager(upgradeDataSource)).execute {
                    reader.createOrFind(tenant, receipt(before, 123_456))
                },
            )
        assertThat(replay.created).isFalse()
        assertThat(replay.storedReceipt.receipt.timing).isNull()
        assertThat(receiptJson(upgradeJdbc, tenant, before)).isEqualTo(original)
        val after = consumption()
        seedConsumption(upgradeDataSource, tenant, after)
        insertLegacy(upgradeJdbc, tenant, after)
        assertThat(reader.find(tenant, after.id)?.receipt?.timing).isNull()
    }

    private fun receiptJson(
        client: JdbcClient,
        tenant: TenantId,
        consumption: CapabilityAuthorizationConsumption,
    ): String =
        client
            .sql(
                """
                SELECT (to_jsonb(r) - 'invocation_started_at' - 'invocation_elapsed_nanoseconds')::TEXT
                FROM capability_invocation_receipts r
                WHERE tenant_id = :tenant AND authorization_consumption_id = :consumption
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("consumption", consumption.id.value)
            .query(String::class.java)
            .single()

    private fun insertLegacy(
        client: JdbcClient,
        tenant: TenantId,
        consumption: CapabilityAuthorizationConsumption,
    ) {
        client
            .sql(legacyReceiptInsert)
            .param("tenant", tenant.value)
            .param("consumption", consumption.id.value)
            .update()
    }

    private fun seedConsumption(
        source: DataSource,
        tenant: TenantId,
        consumption: CapabilityAuthorizationConsumption,
    ) {
        // Isolate receipt constraints from the upstream approval graph. Only
        // consumption fixture insertion disables its parents' FK triggers;
        // receipt constraints, timing CHECKs and mutation triggers remain active.
        val data = consumption
        val client = JdbcClient.create(source)
        TransactionTemplate(DataSourceTransactionManager(source)).executeWithoutResult {
            client.sql("SET LOCAL session_replication_role = replica").update()
            try {
                client
                    .sql(
                        """
                        INSERT INTO capability_authorization_consumptions (
                            tenant_id, authorization_consumption_id, authorization_grant_id,
                            run_id, case_id, policy_revision, step_id, capability, connector,
                            grant_authorized_at, grant_expires_at, consumed_at
                        ) VALUES (
                            :tenant, :consumption, :grant, :run, :case,
                            'ergon.dev/policy/access-restoration/v1', 'unlock-account',
                            'identity.account.unlock', 'identity-stub', :authorized, :expires, :consumed
                        )
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("consumption", data.id.value)
                    .param("grant", data.authorizationGrantId.value)
                    .param("run", data.runId.value)
                    .param("case", data.caseId.value)
                    .param("authorized", data.grantAuthorizedAt.atOffset(ZoneOffset.UTC))
                    .param("expires", data.grantExpiresAt.atOffset(ZoneOffset.UTC))
                    .param("consumed", data.consumedAt.atOffset(ZoneOffset.UTC))
                    .update()
            } finally {
                client.sql("SET LOCAL session_replication_role = origin").update()
            }
        }
    }

    private fun receipt(
        consumption: CapabilityAuthorizationConsumption,
        elapsed: Long,
    ): CapabilityInvocationReceipt =
        CapabilityInvocationReceipt.complete(
            consumption,
            CapabilityInvocationResult(
                CapabilityInvocationOutcome.SUCCEEDED,
                ProviderOperationReference.of("operation/${consumption.id.value}"),
            ),
            NOW.plusSeconds(1),
            CapabilityInvocationTiming(NOW, elapsed),
        )

    private fun consumption(): CapabilityAuthorizationConsumption =
        CapabilityAuthorizationConsumption.rehydrate(
            CapabilityAuthorizationConsumptionSnapshot(
                CapabilityAuthorizationConsumptionId(UUID.randomUUID()),
                CapabilityAuthorizationGrantId(UUID.randomUUID()),
                ResolutionRunId(UUID.randomUUID()),
                CaseId(UUID.randomUUID()),
                ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                ResolutionStepId.of("unlock-account"),
                CapabilityName.of("identity.account.unlock"),
                ConnectorName.of("identity-stub"),
                NOW.minusSeconds(10),
                NOW.plusSeconds(30),
                NOW,
            ),
        )

    companion object {
        private val NOW = Instant.parse("2026-10-12T20:00:00Z")
        private val legacyReceiptInsert =
            """
            INSERT INTO capability_invocation_receipts (
                tenant_id, authorization_consumption_id, authorization_grant_id,
                run_id, case_id, policy_revision, step_id, capability, connector,
                idempotency_key, outcome, provider_operation_reference, consumption_consumed_at, completed_at
            ) SELECT
                tenant_id, authorization_consumption_id, authorization_grant_id,
                run_id, case_id, policy_revision, step_id, capability, connector,
                authorization_consumption_id, 'SUCCEEDED', 'operation/' || authorization_consumption_id,
                consumed_at, consumed_at + INTERVAL '1 second'
            FROM capability_authorization_consumptions
            WHERE tenant_id = :tenant AND authorization_consumption_id = :consumption
            """.trimIndent()

        private val measuredReceiptInsert =
            """
            INSERT INTO capability_invocation_receipts (
                tenant_id, authorization_consumption_id, authorization_grant_id,
                run_id, case_id, policy_revision, step_id, capability, connector,
                idempotency_key, outcome, provider_operation_reference, consumption_consumed_at, completed_at,
                invocation_started_at, invocation_elapsed_nanoseconds
            ) SELECT
                tenant_id, authorization_consumption_id, authorization_grant_id,
                :receiptRun, case_id, policy_revision, step_id, capability, connector,
                authorization_consumption_id, 'SUCCEEDED', 'operation/' || authorization_consumption_id,
                consumed_at, consumed_at + INTERVAL '1 second',
                CAST(:started AS TIMESTAMPTZ), CAST(:elapsed AS BIGINT)
            FROM capability_authorization_consumptions
            WHERE tenant_id = :tenant AND authorization_consumption_id = :consumption
            """.trimIndent()

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
