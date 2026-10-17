package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.AccountAccessState
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.FinalizeRunProofCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAcceptanceService
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.RunProofFinalizationConflictException
import org.ergon.controlplane.resolution.application.RunProofFinalizationService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.run-supervision.proof-finalization.enabled=true",
        "ergon.run-supervision.handover.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
        "spring.flyway.target=20261014120000",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunProofFinalizationIntegrationTest.FixedTime::class)
@Suppress("LongParameterList") // Real HTTP, transaction, case, state and audit boundaries prove this slice.
class RunProofFinalizationIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val service: RunProofFinalizationService,
    @Autowired private val console: AssignedRunConsoleService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val legacy: ResolutionOutcomeProofAcceptanceService,
    @Autowired cases: CaseCommandService,
    @Autowired timelines: CaseTimelineRepository,
    @Autowired results: ResolutionRunCapabilityResultService,
) {
    private val fixtures = RunProofTestFixture(cases, timelines, transactions, jdbc, results, console)

    @BeforeEach
    fun upgradeWithExistingInternalProof() {
        val upgraded =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .table("ergon_flyway_schema_history")
                .load()
        // Startup deliberately targets the released schema. Upgrade once, on
        // whichever isolated scenario runs first; no method ordering is required.
        if (upgraded
                .info()
                .current()
                ?.version
                ?.version != "20261014120000"
        ) {
            return
        }
        // Exercise the supported upgrade with actual case closure and acceptance,
        // not an empty table or a fabricated acceptance that bypasses its constraints.
        val fixture = fixtures.seed()
        val before = legacy.accept(fixture.tenant.value, fixture.run.value).storedEvent
        upgraded.migrate()
        upgraded.validate()
        assertThat(legacy.accept(fixture.tenant.value, fixture.run.value).storedEvent).isEqualTo(before)
        assertThat(
            console
                .get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                .outcomeVerification.status.name,
        ).isEqualTo("ACCEPTED")
        assertThat(
            jdbc
                .sql("SELECT COUNT(*) FROM resolution_run_proof_commands WHERE tenant_id = :tenant")
                .param("tenant", fixture.tenant.value)
                .query(Int::class.java)
                .single(),
        ).isZero()
    }

    @Test
    fun `confirmed exact proof closes once with identical replay and private attribution`() {
        val fixture = fixtures.seed()
        val command = fixtures.command(fixture)
        assertThat(console.get(command.tenantId, command.runId, command.actorId).proofFinalizationSupported).isTrue()
        val first =
            send(command)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.runSequence").value(2))
                .andExpect(jsonPath("$.assessedCaseVersion").value(command.expectedCaseVersion))
                .andExpect(jsonPath("$.caseClosureStreamVersion").value(command.expectedCaseVersion + 1))
                .andExpect(jsonPath("$.actorId").doesNotExist())
                .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertThat(
            send(command)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        val detail = console.get(command.tenantId, command.runId, command.actorId)
        assertThat(detail.state.state.name).isEqualTo("VERIFIED_RESOLVED")
        assertThat(detail.outcomeVerification.status.name).isEqualTo("ACCEPTED")
        assertThat(
            detail.outcomeVerification.evidence
                ?.fact
                ?.factId
                ?.value,
        ).isEqualTo(command.factId)
        assertThat(count("resolution_run_proof_commands", command)).isEqualTo(1)
        assertThat(count("capability_invocation_receipts", command)).isEqualTo(1)
        assertThatThrownBy {
            jdbc
                .sql("DELETE FROM resolution_run_proof_commands WHERE tenant_id = :tenant")
                .param("tenant", command.tenantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        send(command.copy(commandId = UUID.randomUUID())).andExpect(status().isConflict)
        send(command.copy(factId = UUID.randomUUID())).andExpect(status().isConflict)
        send(command.copy(actorId = fixture.other.value)).andExpect(status().isNotFound)
    }

    @Test
    fun `stale review never substitutes later matching or mismatching evidence`() {
        val fixture = fixtures.seed()
        val command = fixtures.command(fixture)
        for (stale in listOf(
            command.copy(expectedRunVersion = 0),
            command.copy(expectedSupervisionRevision = RunSupervisionRevision(1)),
            command.copy(factId = UUID.randomUUID()),
            command.copy(observationId = UUID.randomUUID()),
        )) {
            send(stale).andExpect(status().isConflict)
        }
        fixtures.append(fixture, AccountAccessState.ACTIVE)
        send(command).andExpect(status().isConflict)
        val later = fixtures.command(fixture)
        fixtures.append(fixture, AccountAccessState.LOCKED)
        send(later).andExpect(status().isConflict)
        assertThat(count("resolution_run_proof_commands", command)).isZero()
        assertThat(
            console
                .get(command.tenantId, command.runId, command.actorId)
                .state.state.name,
        ).isEqualTo("VERIFYING")
    }

    @Test
    fun `lost supervision and expired authority deny first acceptance without disclosing proof`() {
        val fixture = fixtures.seed()
        val command = fixtures.command(fixture)
        send(command.copy(actorId = fixture.expired.value)).andExpect(status().isNotFound)
        send(command.copy(actorId = fixture.other.value)).andExpect(status().isNotFound)
        assertThatThrownBy { service.finalizeProof(command.copy(tenantId = UUID.randomUUID())) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
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
        send(command).andExpect(status().isNotFound)
        send(command.copy(actorId = fixture.incoming.value)).andExpect(status().isConflict)
        send(command.copy(actorId = fixture.incoming.value, expectedSupervisionRevision = RunSupervisionRevision(1)))
            .andExpect(status().isCreated)
    }

    @Test
    fun `case closure proof run state and browser receipt roll back together`() {
        val fixture = fixtures.seed()
        val command = fixtures.command(fixture)
        assertThatThrownBy {
            transactions.required {
                service.finalizeProof(command)
                error("controlled transaction failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        val detail = console.get(command.tenantId, command.runId, command.actorId)
        assertThat(detail.state.state.name).isEqualTo("VERIFYING")
        assertThat(detail.outcomeVerification.caseStreamVersion).isEqualTo(command.expectedCaseVersion)
        assertThat(count("resolution_outcome_proof_acceptances", command)).isZero()
        assertThat(count("resolution_run_proof_commands", command)).isZero()
        send(command).andExpect(status().isCreated)
    }

    @Test
    fun `concurrent distinct confirmations yield one closure and one conflict`() {
        val command = fixtures.command(fixtures.seed())
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val futures =
                listOf(command, command.copy(commandId = UUID.randomUUID())).map { intent ->
                    pool.submit(
                        Callable {
                            check(start.await(10, TimeUnit.SECONDS))
                            try {
                                service.finalizeProof(intent)
                                "created"
                            } catch (_: RunProofFinalizationConflictException) {
                                "conflict"
                            }
                        },
                    )
                }
            start.countDown()
            assertThat(futures.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder("created", "conflict")
        }
        assertThat(count("resolution_run_proof_commands", command)).isEqualTo(1)
    }

    @Test
    fun `browser cannot relabel an existing internal acceptance as its own command`() {
        val command = fixtures.command(fixtures.seed())
        legacy.accept(command.tenantId, command.runId)
        send(command).andExpect(status().isConflict)
        assertThat(count("resolution_run_proof_commands", command)).isZero()
    }

    @Test
    fun `browser boundary requires session csrf and valid reviewed coordinates`() {
        val command = fixtures.command(fixtures.seed())
        mvc
            .perform(post(path(command)).contentType(MediaType.APPLICATION_JSON).content(body(command)))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path(command))
                    .with(session(command.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        mvc
            .perform(
                post(path(command))
                    .with(session(command.actorId))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        body(command).replace(
                            "\"expectedCaseVersion\":${command.expectedCaseVersion}",
                            "\"expectedCaseVersion\":0",
                        ),
                    ),
            ).andExpect(status().isBadRequest)
        assertThat(count("resolution_run_proof_commands", command)).isZero()
    }

    private fun send(command: FinalizeRunProofCommand) =
        mvc.perform(
            post(path(command))
                .with(session(command.actorId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(command)),
        )

    private fun path(command: FinalizeRunProofCommand) =
        "/bff/v1/tenants/${command.tenantId}/resolution-runs/${command.runId}/proof-finalizations"

    private fun body(command: FinalizeRunProofCommand) =
        """
        {
            "commandId":"${command.commandId}",
            "expectedSupervisionRevision":${command.expectedSupervisionRevision.value},
            "expectedRunVersion":${command.expectedRunVersion},
            "expectedCaseVersion":${command.expectedCaseVersion},
            "factId":"${command.factId}",
            "observationId":"${command.observationId}"
        }
        """.trimIndent()

    private fun session(actor: UUID) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("resolver-$actor")
        }

    private fun count(
        table: String,
        command: FinalizeRunProofCommand,
    ) = jdbc
        .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant AND run_id = :run")
        .param("tenant", command.tenantId)
        .param("run", command.runId)
        .query(Int::class.java)
        .single()

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun proofClock(): Clock = Clock.fixed(RunProofTestFixture.NOW, ZoneOffset.UTC)
    }

    companion object {
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
