package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.AssignRunSupervisorCommand
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationService
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.ergon.controlplane.resolution.application.RunCommandControl
import org.ergon.controlplane.resolution.application.RunControlAction
import org.ergon.controlplane.resolution.application.RunControlCommand
import org.ergon.controlplane.resolution.application.RunControlService
import org.ergon.controlplane.resolution.application.RunRetrySuccessorService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.StoredResolutionRunRetry
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.retry.enabled=false",
        "ergon.run-supervision.handover.enabled=true",
        "ergon.run-supervision.control.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunRetrySuccessorIntegrationTest.FixedTime::class)
@Suppress("LongParameterList") // Real runtime history, authority and assignment gates exercise the read-only boundary.
class RunRetrySuccessorIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val service: RunRetrySuccessorService,
    @Autowired private val internal: ResolutionRunRetryService,
    @Autowired private val escalations: ResolutionRunEscalationService,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val assignmentCommands: RunSupervisorAssignmentService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val controls: RunControlService,
    @Autowired cases: CaseCommandService,
    @Autowired timelines: CaseTimelineRepository,
    @Autowired results: ResolutionRunCapabilityResultService,
) {
    private val fixtures = RunRetryTestFixture(cases, timelines, transactions, jdbc, results)

    @MockitoSpyBean
    private lateinit var retries: ResolutionRunRetryRepository

    @Test
    fun `no retry and an unassigned internal successor have identical safe absence`() {
        val fixture = fixtures.seed()
        val absent =
            read(fixture)
                .andExpect(jsonPath("$.successor").isEmpty())
                .andReturn()
                .response.contentAsString
        val successor = retry(fixture)
        val unassigned =
            read(fixture)
                .andExpect(jsonPath("$.successor").isEmpty())
                .andReturn()
                .response.contentAsString
        assertThat(unassigned).isEqualTo(absent).doesNotContain(successor.value.toString())
        assertThat(count("resolution_runs", fixture)).isEqualTo(2)
        assertThat(count("resolution_run_supervisor_assignments", fixture)).isEqualTo(1)
        assertNoReadEffects(fixture)
    }

    @Test
    fun `trusted assignment reveals a terminal internal successor with commands disabled and no control lease`() {
        val fixture = fixtures.seed()
        val successor = retry(fixture)
        assign(fixture, successor)
        fixtures.failRun(fixture.tenant, successor)
        escalations.escalate(fixture.tenant.value, successor.value, fixture.outgoing.value)
        repeat(2) {
            val response =
                read(fixture)
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$.successor.length()").value(2))
                    .andExpect(jsonPath("$.observedAt").value(RunRetryTestFixture.NOW.toString()))
                    .andExpect(jsonPath("$.supervisionRevision").value(0))
                    .andExpect(jsonPath("$.successor.runId").value(successor.value.toString()))
                    .andExpect(jsonPath("$.successor.supervisionRevision").value(0))
                    .andReturn()
                    .response.contentAsString
            assertThat(response).doesNotContain(
                fixture.outgoing.value.toString(),
                fixture.outgoingEvidence.value.toString(),
                "commandId",
                "eventId",
                "authority",
                "provider",
                "caseId",
                "state",
                "PRIVATE_",
            )
        }
        assertThat(count("resolution_runs", fixture)).isEqualTo(2)
        assertThat(count("resolution_run_supervisor_assignments", fixture)).isEqualTo(2)
        assertThat(count("capability_invocation_receipts", fixture)).isEqualTo(2)
        assertNoReadEffects(fixture)
    }

    @Test
    fun `handover discloses the link only when source and target are independently supervised`() {
        val fixture = fixtures.seed()
        handover(fixture, fixture.run)
        val successor = retry(fixture)
        assign(fixture, successor)
        read(fixture, fixture.incoming).andExpect(jsonPath("$.successor").isEmpty())
        mvc
            .perform(get(path(fixture)).with(session(fixture.outgoing)))
            .andExpect(status().isNotFound)
            .andExpect(header().string("Cache-Control", "no-store"))
        handover(fixture, successor)
        read(fixture, fixture.incoming)
            .andExpect(jsonPath("$.supervisionRevision").value(1))
            .andExpect(jsonPath("$.successor.runId").value(successor.value.toString()))
            .andExpect(jsonPath("$.successor.supervisionRevision").value(1))
        mvc
            .perform(get(path(fixture)).with(session(fixture.outgoing)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:assigned-resolution-run-not-found"))
    }

    @Test
    fun `missing foreign unassigned and expired predecessor gates reject before retry lookup`() {
        val fixture = fixtures.seed()
        var reads = 0
        val reader =
            object : ResolutionRunRetryRepository by retries {
                override fun find(
                    tenantId: TenantId,
                    runId: ResolutionRunId,
                ): StoredResolutionRunRetry? {
                    reads++
                    error("history must not be read")
                }
            }
        val guarded = serviceWith(reader)
        val attempts =
            listOf(
                Triple(fixture.tenant.value, fixture.run.value, fixture.incoming.value),
                Triple(fixture.tenant.value, fixture.run.value, fixture.expired.value),
                Triple(fixture.tenant.value, UUID.randomUUID(), fixture.outgoing.value),
                Triple(UUID.randomUUID(), fixture.run.value, fixture.outgoing.value),
            )
        attempts.forEach { (tenant, run, actor) ->
            assertThatThrownBy { guarded.get(tenant, run, actor) }
                .isInstanceOf(AssignedRunNotFoundException::class.java)
        }
        assertThat(reads).isZero()
        val successor = retry(fixture)
        assertThatThrownBy { guarded.get(fixture.tenant.value, successor.value, fixture.outgoing.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThat(reads).isZero()
    }

    @Test
    fun `expired authority denies retained links and storage failures are not absence`() {
        val fixture = fixtures.seed()
        val successor = retry(fixture)
        assign(fixture, successor)
        assertThat(service.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).successor).isNotNull()
        val failure =
            object : ResolutionRunRetryRepository by retries {
                override fun find(
                    tenantId: TenantId,
                    runId: ResolutionRunId,
                ): StoredResolutionRunRetry? = throw DataAccessResourceFailureException("PRIVATE_DATABASE_CAUSE")
            }
        assertThatThrownBy { serviceWith(failure).get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value) }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
        val later = Clock.fixed(RunRetryTestFixture.NOW.plusSeconds(7200), ZoneOffset.UTC)
        val expired = RunRetrySuccessorService(assignments, authorities, retries, transactions, later)
        assertThatThrownBy { expired.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    @Test
    fun `persistence failure returns cause-free unavailability instead of an empty successor`() {
        val fixture = fixtures.seed()
        Mockito
            .doThrow(DataAccessResourceFailureException("PRIVATE_DATABASE_CAUSE"))
            .`when`(retries)
            .find(fixture.tenant, fixture.run)
        val response =
            mvc
                .perform(get(path(fixture)).with(session(fixture.outgoing)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-retry-unavailable"))
                .andExpect(jsonPath("$.successor").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain("PRIVATE_DATABASE_CAUSE", "stackTrace", "DataAccess", "jdbc")
    }

    @Test
    fun `session identity invalid coordinates and foreign tenants use no-store safe failures`() {
        val fixture = fixtures.seed()
        mvc.perform(get(path(fixture))).andExpect(status().isUnauthorized)
        mvc
            .perform(get(path(fixture)).with(session(fixture.expired)))
            .andExpect(status().isNotFound)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc
            .perform(
                get(path(fixture).replace(fixture.tenant.value.toString(), UUID.randomUUID().toString()))
                    .with(session(fixture.outgoing)),
            ).andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc
            .perform(
                get(path(fixture).replace(fixture.run.value.toString(), "not-a-uuid"))
                    .with(session(fixture.outgoing)),
            ).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-run-retry"))
    }

    private fun serviceWith(reader: ResolutionRunRetryRepository) =
        RunRetrySuccessorService(
            assignments,
            authorities,
            reader,
            transactions,
            Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC),
        )

    private fun retry(fixture: RunSupervisionDatabaseFixture): ResolutionRunId =
        internal
            .retry(
                fixture.tenant.value,
                fixture.run.value,
                4,
                fixture.outgoing.value,
            ).replacementRun.run.id

    private fun assign(
        fixture: RunSupervisionDatabaseFixture,
        run: ResolutionRunId,
    ) {
        assignmentCommands.assign(
            AssignRunSupervisorCommand(
                fixture.tenant.value,
                run.value,
                fixture.outgoing.value,
                UUID.randomUUID(),
                "trusted-dispatcher",
            ),
        )
    }

    private fun handover(
        fixture: RunSupervisionDatabaseFixture,
        run: ResolutionRunId,
    ) {
        // Fixture writes respect control enforcement; the relationship read never acquires a lease.
        val holder = UUID.randomUUID()
        val lease =
            controls.change(
                RunControlCommand(
                    fixture.tenant.value,
                    run.value,
                    fixture.outgoing.value,
                    UUID.randomUUID(),
                    RunSupervisionRevision(0),
                    RunControlAction.ACQUIRE,
                    null,
                ),
                holder,
            )
        handovers.handover(
            HandoverRunSupervisorCommand(
                fixture.tenant.value,
                run.value,
                fixture.outgoing.value,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            ),
            RunCommandControl(lease.fence, holder),
        )
    }

    private fun read(
        fixture: RunSupervisionDatabaseFixture,
        actor: HumanActorId = fixture.outgoing,
    ) = mvc
        .perform(get(path(fixture)).with(session(actor)))
        .andExpect(status().isOk)
        .andExpect(header().string("Cache-Control", "no-store"))

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test").subject("resolver-${actor.value}")
        }

    private fun path(fixture: RunSupervisionDatabaseFixture): String =
        "/bff/v1/tenants/${fixture.tenant.value}/resolution-runs/${fixture.run.value}/retry-successor"

    private fun count(
        table: String,
        fixture: RunSupervisionDatabaseFixture,
    ): Int =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
            .param("tenant", fixture.tenant.value)
            .query(Int::class.java)
            .single()

    private fun assertNoReadEffects(fixture: RunSupervisionDatabaseFixture) {
        listOf(
            "resolution_run_retry_commands",
            "resolution_run_control_events",
            "capability_authorization_grants",
            "capability_authorization_consumptions",
        ).forEach {
            assertThat(count(it, fixture)).describedAs(it).isZero()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun successorClock(): Clock = Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC)
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
