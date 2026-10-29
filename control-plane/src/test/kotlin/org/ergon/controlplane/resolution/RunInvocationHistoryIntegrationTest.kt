package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.adapter.out.connector.FakeIdentityCapabilityConnector
import org.ergon.controlplane.resolution.adapter.out.persistence.PostgresRunInvocationHistoryReader
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionRepository
import org.ergon.controlplane.resolution.application.CapabilityConnectorGateway
import org.ergon.controlplane.resolution.application.CapabilityInvocationAttemptIdentityGenerator
import org.ergon.controlplane.resolution.application.CapabilityInvocationAttemptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.controlplane.resolution.application.CapabilityInvocationCommand
import org.ergon.controlplane.resolution.application.CapabilityInvocationCompletion
import org.ergon.controlplane.resolution.application.CapabilityInvocationPreparation
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationService
import org.ergon.controlplane.resolution.application.CapabilityInvocationTicker
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.RecordedInvocationAttempts
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionService
import org.ergon.controlplane.resolution.application.RunInvocationHistoryReader
import org.ergon.controlplane.resolution.application.RunInvocationHistoryService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationTiming
import org.ergon.resolution.domain.ResolutionRunId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.run-supervision.execution.enabled=true",
        "ergon.run-supervision.handover.enabled=true", "spring.flyway.target=20261019120000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(
    BrowserSessionTestClientConfiguration::class,
    RunInvocationHistoryIntegrationTest.InvocationTestConfiguration::class,
)
@Suppress("LongParameterList") // Explicit ports prove I/O and authority boundaries.
class RunInvocationHistoryIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val admissions: RunExecutionAdmissionService,
    @Autowired requests: ApprovalRequestService,
    @Autowired decisions: ApprovalDecisionService,
    @Autowired private val consumptions: CapabilityAuthorizationConsumptionRepository,
    @Autowired private val receipts: CapabilityInvocationReceiptRepository,
    @Autowired private val audit: CapabilityInvocationAttemptRepository,
    @Autowired private val identities: CapabilityInvocationAttemptIdentityGenerator,
    @Autowired private val history: RunInvocationHistoryService,
    @Autowired private val reader: ControlledInvocationHistoryReader,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val gateway: RunExecutionTestConnector,
    @Autowired private val flyway: Flyway,
) {
    private val fixtures = RunExecutionTestFixture(jdbc, transactions, requests, decisions, NOW)

    @BeforeEach
    fun upgradeWithAReleasedWriterReceipt() =
        synchronized(postgres) {
            if (flyway
                    .info()
                    .current()
                    .version
                    .toString() == "20261019120000"
            ) {
                val (fixture, command) = fixtures.seed()
                val admitted = admissions.admit(command)
                val consumption =
                    checkNotNull(consumptions.find(fixture.tenant, admitted.stored.consumptionId)).consumption
                val oldCommand = CapabilityInvocationCommand(fixture.tenant, consumption)
                val result = FakeIdentityCapabilityConnector().invoke(oldCommand)
                val old =
                    transactions.required {
                        receipts.createOrFind(
                            fixture.tenant,
                            CapabilityInvocationReceipt.complete(
                                consumption,
                                result,
                                NOW,
                                CapabilityInvocationTiming(NOW, 123L),
                            ),
                        )
                    }
                val upgraded =
                    Flyway
                        .configure()
                        .configuration(flyway.configuration)
                        .target("latest")
                        .load()
                upgraded.migrate()
                upgraded.validate()
                assertThat(invocation().invoke(command.tenantId, consumption.id.value).storedReceipt)
                    .isEqualTo(old.storedReceipt)
                assertThat(reader.read(fixture.tenant, fixture.run).entries).isEmpty()
                assertThat(count("capability_invocation_preparations", command.tenantId)).isZero()
            }
        }

    @ParameterizedTest
    @EnumSource(CapabilityInvocationOutcome::class)
    fun `gateway results retain precise timing without replay IO`(outcome: CapabilityInvocationOutcome) {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        val elapsed = 9_007_199_254_740_993L
        val ticks = ArrayDeque(listOf(0L, elapsed))
        gateway.onCall(command.tenantId) {
            assertThat(count("capability_invocation_preparations", command.tenantId)).isEqualTo(1)
            assertThat(count("capability_invocation_completions", command.tenantId)).isZero()
        }
        val service =
            invocation(
                ticker = CapabilityInvocationTicker { ticks.removeFirst() },
                connector = CapabilityConnectorGateway { gateway.invoke(it).copy(outcome = outcome) },
            )
        val first = service.invoke(command.tenantId, admitted.stored.consumptionId.value)
        assertThat(
            invocation(ticker = CapabilityInvocationTicker { error("replay sampled") })
                .invoke(command.tenantId, admitted.stored.consumptionId.value)
                .storedReceipt,
        ).isEqualTo(first.storedReceipt)
        val recorded =
            history
                .get(command.tenantId, command.runId, command.actorId)
                .attempts.entries
                .single()
        assertThat(
            recorded.completion
                ?.call
                ?.timing
                ?.elapsedNanoseconds,
        ).isEqualTo(elapsed)
        val response =
            mvc
                .perform(get(url(command)).with(session(fixture.outgoing)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.supervisionRevision").value(0))
                .andExpect(jsonPath("$.entries[0].completion.outcome").value(outcome.name))
                .andExpect(jsonPath("$.entries[0].completion.timing.elapsedNanoseconds").value(elapsed.toString()))
                .andReturn()
                .response.contentAsString
        for (secret in listOf(command.actorId, command.commandId, admitted.stored.consumptionId.value)) {
            assertThat(response).doesNotContain(secret.toString())
        }
        for (field in listOf(
            "invocationAttemptId",
            "attemptId",
            "commandId",
            "actorId",
            "authorizationGrantId",
            "providerOperationReference",
            "control",
        )) {
            assertThat(response).doesNotContain("\"$field\"")
        }
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        assertThat(count("capability_invocation_completions", command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_run_control_events", command.tenantId)).isZero()
    }

    @Test
    fun `thrown gateway calls record uncertainty without receipt and explicit recovery keeps both samples`() {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        val failure = IllegalStateException("PRIVATE_SYNTHETIC_GATEWAY_CAUSE")
        gateway.onCall(command.tenantId) { throw failure }
        assertThatThrownBy {
            invocation().invoke(command.tenantId, admitted.stored.consumptionId.value)
        }.isSameAs(failure)
        val unknown = reader.read(fixture.tenant, fixture.run).entries.single()
        assertThat(unknown.completion?.call?.outcome).isEqualTo(CapabilityInvocationCallOutcome.UNKNOWN)
        assertThat(count("capability_invocation_receipts", command.tenantId)).isZero()
        val response =
            mvc
                .perform(get(url(command)).with(session(fixture.outgoing)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.entries[0].completion.outcome").value("UNKNOWN"))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain(failure.message)
        gateway.onCall(command.tenantId) { }
        invocation().invoke(command.tenantId, admitted.stored.consumptionId.value)
        assertThat(reader.read(fixture.tenant, fixture.run).entries.map { it.completion?.call?.outcome })
            .containsExactlyInAnyOrder(
                CapabilityInvocationCallOutcome.UNKNOWN,
                CapabilityInvocationCallOutcome.SUCCEEDED,
            )
        assertThat(gateway.operationCount(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `completion failure rolls back the receipt and leaves only a committed uncertain preparation`() {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        val failure = IllegalStateException("synthetic completion write failed")
        val broken =
            object : CapabilityInvocationAttemptRepository by audit {
                override fun complete(
                    tenant: TenantId,
                    attemptId: UUID,
                    completion: CapabilityInvocationCompletion,
                ): Unit = throw failure
            }
        assertThatThrownBy {
            invocation(attempts = broken).invoke(command.tenantId, admitted.stored.consumptionId.value)
        }.isSameAs(failure)
        assertThat(count("capability_invocation_receipts", command.tenantId)).isZero()
        assertThat(
            reader
                .read(fixture.tenant, fixture.run)
                .entries
                .single()
                .completion,
        ).isNull()
        mvc
            .perform(get(url(command)).with(session(fixture.outgoing)))
            .andExpect(jsonPath("$.entries[0].completion").isEmpty())
        invocation().invoke(command.tenantId, admitted.stored.consumptionId.value)
        assertThat(reader.read(fixture.tenant, fixture.run).entries.map { it.completion != null })
            .containsExactlyInAnyOrder(false, true)
        assertThat(gateway.operationCount(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `concurrent gateway callers retain independent timings and the same complete winning receipt`() {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        val entered = CountDownLatch(2)
        gateway.onCall(command.tenantId) {
            entered.countDown()
            check(entered.await(15, TimeUnit.SECONDS))
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(101L, 202L).map { elapsed ->
                    pool.submit<org.ergon.controlplane.resolution.application.CapabilityInvocationExecution> {
                        val ticks = ArrayDeque(listOf(0L, elapsed))
                        invocation(ticker = CapabilityInvocationTicker { ticks.removeFirst() })
                            .invoke(command.tenantId, admitted.stored.consumptionId.value)
                    }
                }
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.storedReceipt }).containsOnly(results.first().storedReceipt)
            assertThat(
                reader.read(fixture.tenant, fixture.run).entries.map {
                    it.completion
                        ?.call
                        ?.timing
                        ?.elapsedNanoseconds
                },
            ).containsExactlyInAnyOrder(101L, 202L)
            assertThat(
                results
                    .first()
                    .storedReceipt.receipt.timing
                    ?.elapsedNanoseconds,
            ).isIn(101L, 202L)
            assertThat(count("capability_invocation_receipts", command.tenantId)).isEqualTo(1)
            assertThat(gateway.operationCount(command.tenantId)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `history follows current supervision and rejects expired foreign and missing access before audit`() {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        invocation().invoke(command.tenantId, admitted.stored.consumptionId.value)
        val before = history.get(command.tenantId, command.runId, command.actorId).attempts
        handovers.handover(
            HandoverRunSupervisorCommand(
                command.tenantId,
                command.runId,
                command.actorId,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            ),
        )
        assertThat(history.get(command.tenantId, command.runId, fixture.incoming.value).attempts).isEqualTo(before)
        mvc
            .perform(get(url(command)).with(session(fixture.incoming)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.supervisionRevision").value(1))
        var reads = 0
        val guarded =
            RunInvocationHistoryService(
                assignments,
                authorities,
                RunInvocationHistoryReader { _, _ ->
                    reads++
                    error("denied history lookup")
                },
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
            )
        for ((tenant, run, actor) in listOf(
            Triple(command.tenantId, command.runId, command.actorId),
            Triple(command.tenantId, command.runId, fixture.expired.value),
            Triple(UUID.randomUUID(), command.runId, fixture.incoming.value),
            Triple(command.tenantId, UUID.randomUUID(), fixture.incoming.value),
        )) {
            assertThatThrownBy { guarded.get(tenant, run, actor) }
                .isInstanceOf(AssignedRunNotFoundException::class.java)
        }
        assertThat(reads).isZero()
        for (actor in listOf(fixture.outgoing, fixture.expired)) {
            mvc
                .perform(get(url(command)).with(session(actor)))
                .andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:assigned-resolution-run-not-found"))
        }
        mvc.perform(get(url(command))).andExpect(status().isUnauthorized)
        assertThat(reader.read(TenantId(UUID.randomUUID()), fixture.run).entries).isEmpty()
    }

    @Test
    fun `bounded preparations use deterministic private ties without treating times as causal`() {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        jdbc
            .sql(
                """
                INSERT INTO capability_invocation_preparations (
                    tenant_id, invocation_attempt_id, authorization_consumption_id, prepared_at, recorded_at
                )
                SELECT :tenant, LPAD(TO_HEX(n), 32, '0')::UUID, :consumption,
                    :at - n * INTERVAL '1 second', :at
                FROM GENERATE_SERIES(1, 100) n
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("consumption", admitted.stored.consumptionId.value)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .update()
        assertThat(reader.read(fixture.tenant, fixture.run).truncated).isFalse()
        // Keep every sort coordinate explicit; this test must not depend on
        // the database wall clock being later than its fixed observations.
        jdbc
            .sql(
                """
                INSERT INTO capability_invocation_preparations (
                    tenant_id, invocation_attempt_id, authorization_consumption_id, prepared_at, recorded_at
                ) VALUES (:tenant, :id, :consumption, :at, :at)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("id", UUID(0, 101))
            .param("consumption", admitted.stored.consumptionId.value)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .update()
        val result = reader.read(fixture.tenant, fixture.run)
        assertThat(result.truncated).isTrue()
        assertThat(result.entries).hasSize(100)
        assertThat(result.entries.first().preparedAt).isEqualTo(NOW.minusSeconds(1))
        assertThat(result.entries.last().preparedAt).isEqualTo(NOW.minusSeconds(100))
        assertThat(result.entries.map { it.completion }).containsOnlyNulls()
        assertThat(gateway.calls(command.tenantId)).isZero()
    }

    @Test
    fun `audit enforces scoped references valid completions unique identities and immutability`() {
        val (fixture, command) = fixtures.seed()
        val admitted = admissions.admit(command)
        val id = UUID.randomUUID()
        val preparation = CapabilityInvocationPreparation(id, admitted.stored.consumptionId, NOW)
        assertThatThrownBy { transactions.required { audit.prepare(TenantId(UUID.randomUUID()), preparation) } }
            .isInstanceOf(DataAccessException::class.java)
        transactions.required { audit.prepare(fixture.tenant, preparation) }
        assertThatThrownBy { transactions.required { audit.prepare(fixture.tenant, preparation) } }
            .isInstanceOf(DataAccessException::class.java)
        val completion =
            CapabilityInvocationCompletion(
                CapabilityInvocationCallOutcome.UNKNOWN,
                NOW,
                CapabilityInvocationTiming(NOW, 0),
            )
        assertThatThrownBy { transactions.required { audit.complete(TenantId(UUID.randomUUID()), id, completion) } }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { transactions.required { audit.complete(fixture.tenant, UUID.randomUUID(), completion) } }
            .isInstanceOf(DataAccessException::class.java)
        for ((outcome, elapsed) in listOf("RUNNING" to 0L, "SUCCEEDED" to -1L)) {
            assertThatThrownBy {
                jdbc
                    .sql(
                        """
                        INSERT INTO capability_invocation_completions (
                            tenant_id, invocation_attempt_id, outcome,
                            invocation_started_at, completed_at, elapsed_nanoseconds
                        ) VALUES (:tenant, :id, :outcome, :at, :at, :elapsed)
                        """.trimIndent(),
                    ).param("tenant", command.tenantId)
                    .param("id", id)
                    .param("outcome", outcome)
                    .param("at", NOW.atOffset(ZoneOffset.UTC))
                    .param("elapsed", elapsed)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
        transactions.required { audit.complete(fixture.tenant, id, completion) }
        assertThatThrownBy { transactions.required { audit.complete(fixture.tenant, id, completion) } }
            .isInstanceOf(DataAccessException::class.java)
        for (table in listOf("capability_invocation_preparations", "capability_invocation_completions")) {
            for (statement in listOf("UPDATE $table SET recorded_at = recorded_at", "DELETE FROM $table")) {
                assertThatThrownBy {
                    jdbc.sql("$statement WHERE tenant_id = :tenant").param("tenant", command.tenantId).update()
                }.isInstanceOf(DataAccessException::class.java)
            }
        }
    }

    @Test
    fun `empty authorized history and failed storage never turn into invented calls or leaked errors`() {
        val (fixture, command) = fixtures.seed()
        mvc
            .perform(get(url(command)).with(session(fixture.outgoing)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries").isEmpty())
            .andExpect(jsonPath("$.truncated").value(false))
        assertThat(count("resolution_run_execution_commands", command.tenantId)).isZero()
        reader.failedTenants.add(fixture.tenant)
        val response =
            mvc
                .perform(get(url(command)).with(session(fixture.outgoing)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-execution-unavailable"))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain("PRIVATE_SYNTHETIC_READ_FAILURE")
        mvc
            .perform(
                get(
                    "/bff/v1/tenants/not-a-uuid/resolution-runs/{runId}/invocation-history",
                    command.runId,
                ).with(session(fixture.outgoing)),
            ).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `complete migration chain also validates against an independent empty schema`() {
        val schema = "invocation_empty_" + UUID.randomUUID().toString().replace("-", "")
        val empty =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
        empty.migrate()
        empty.validate()
        assertThat(
            empty
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261029220000")
    }

    private fun invocation(
        attempts: CapabilityInvocationAttemptRepository = audit,
        ticker: CapabilityInvocationTicker = CapabilityInvocationTicker { 0L },
        connector: CapabilityConnectorGateway = gateway,
    ): CapabilityInvocationService =
        CapabilityInvocationService(
            consumptions,
            receipts,
            connector,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            ticker,
            attempts,
            identities,
        )

    private fun count(
        table: String,
        tenant: UUID,
    ): Int =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
            .param("tenant", tenant)
            .query(Int::class.java)
            .single()

    private fun url(command: ExecuteRunCommand): String =
        "/bff/v1/tenants/${command.tenantId}/resolution-runs/${command.runId}/invocation-history"

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("resolver-${actor.value}")
        }

    @TestConfiguration(proxyBeanMethods = false)
    class InvocationTestConfiguration {
        @Bean @Primary
        fun invocationTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean @Primary
        fun invocationTestConnector(jdbc: JdbcClient) = RunExecutionTestConnector(jdbc)

        @Bean @Primary
        fun controlledReader(delegate: PostgresRunInvocationHistoryReader) = ControlledInvocationHistoryReader(delegate)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-20T08:00:00Z")

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

/** Per-tenant faults avoid mutating SQL schema or affecting concurrently isolated scenarios. */
class ControlledInvocationHistoryReader(
    private val delegate: RunInvocationHistoryReader,
) : RunInvocationHistoryReader {
    val failedTenants: MutableSet<TenantId> = ConcurrentHashMap.newKeySet()

    override fun read(
        tenant: TenantId,
        run: ResolutionRunId,
    ): RecordedInvocationAttempts {
        check(tenant !in failedTenants) { "PRIVATE_SYNTHETIC_READ_FAILURE" }
        return delegate.read(tenant, run)
    }
}
