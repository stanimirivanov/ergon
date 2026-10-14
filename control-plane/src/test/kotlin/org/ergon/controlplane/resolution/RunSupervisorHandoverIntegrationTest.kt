package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandover
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.identity.domain.HumanActorId
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.handover.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunSupervisorHandoverIntegrationTest.FixedTime::class)
@Suppress("LongParameterList") // These are real adapter/service dependencies, not mocked controller results.
class RunSupervisorHandoverIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val service: RunSupervisorHandoverService,
    @Autowired private val console: AssignedRunConsoleService,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val handovers: RunSupervisorHandoverRepository,
) {
    @Test
    fun `confirmed browser handover changes the real access gate without leaking private audit attribution`() {
        val fixture = seed()
        mvc
            .perform(get(path(fixture, "console")).with(session(fixture.outgoing)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.supervisionRevision").value(0))
            .andExpect(jsonPath("$.handoverSupported").value(true))
        mvc
            .perform(
                get(path(fixture, "handover-candidates") + "?expectedSupervisionRevision=0&limit=1")
                    .with(session(fixture.outgoing)),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.nextActorId").isString)
            .andExpect(jsonPath("$.entries[0].identitySubject").doesNotExist())
        val command = command(fixture)
        mvc
            .perform(
                post(path(fixture, "supervisor-handovers"))
                    .with(session(fixture.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.commandId").value(command.commandId.toString()))
            .andExpect(jsonPath("$.supervisionRevision").value(1))
            .andExpect(jsonPath("$.previousActorId").doesNotExist())
            .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
        mvc
            .perform(get(path(fixture, "console")).with(session(fixture.outgoing)))
            .andExpect(status().isNotFound)
        mvc
            .perform(get(path(fixture, "console")).with(session(fixture.incoming)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.supervisionRevision").value(1))
        assertThat(console.list(fixture.tenant.value, fixture.outgoing.value, 30, null).entries).isEmpty()
        assertThat(
            console
                .list(fixture.tenant.value, fixture.incoming.value, 30, null)
                .entries
                .single()
                .supervisionRevision.value,
        ).isEqualTo(1)
        assertThat(assignments.findByRun(fixture.tenant, fixture.run)).isEqualTo(fixture.initial)
    }

    @Test
    fun `historical replay survives later handback without applying the original transfer again`() {
        val fixture = seed()
        val first = command(fixture)
        val original = service.handover(first)
        assertThat(service.handover(first).stored).isEqualTo(original.stored)
        val returned =
            first.copy(
                actorId = fixture.incoming.value,
                supervisorActorId = fixture.outgoing.value,
                commandId = UUID.randomUUID(),
                expectedRevision = RunSupervisionRevision(1),
            )
        service.handover(returned)
        assertThat(service.handover(first).created).isFalse()
        assertThat(
            console.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).supervisionRevision.value,
        ).isEqualTo(2)
        assertThat(eventCount(fixture)).isEqualTo(3)
        mvc
            .perform(
                post(path(fixture, "supervisor-handovers"))
                    .with(session(fixture.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(first.copy(commandId = UUID.randomUUID()))),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-supervisor-handover-conflict"))
        assertThat(eventCount(fixture)).isEqualTo(3)
    }

    @Test
    fun `CSRF non-owner expired authority and foreign recipient failures leave supervision unchanged`() {
        val fixture = seed()
        val request =
            post(path(fixture, "supervisor-handovers"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(command(fixture)))
        mvc.perform(request.with(session(fixture.outgoing))).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path(fixture, "supervisor-handovers"))
                    .with(session(fixture.other))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command(fixture))),
            ).andExpect(status().isNotFound)
        mvc
            .perform(
                get(path(fixture, "handover-candidates") + "?expectedSupervisionRevision=0")
                    .with(session(fixture.expired)),
            ).andExpect(status().isNotFound)
        for (recipient in listOf(fixture.expired.value, fixture.outgoing.value, UUID.randomUUID())) {
            mvc
                .perform(
                    post(path(fixture, "supervisor-handovers"))
                        .with(session(fixture.outgoing))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(command(fixture).copy(supervisorActorId = recipient))),
                ).andExpect(status().isConflict)
        }
        mvc.perform(post(path(fixture, "supervisor-handovers"))).andExpect(status().isUnauthorized)
        assertThat(eventCount(fixture)).isEqualTo(1)
    }

    @Test
    fun `competing transfer commands commit exactly one new epoch`() {
        val fixture = seed()
        val first = command(fixture)
        val second = first.copy(commandId = UUID.randomUUID(), supervisorActorId = fixture.other.value)
        val results = compete(first, second)
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThat(eventCount(fixture)).isEqualTo(2)
    }

    @Test
    fun `concurrent exact command replays share one durable receipt`() {
        val fixture = seed()
        val command = command(fixture)
        val results = compete(command, command).map { it.getOrThrow() }
        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results.map { it.stored }.distinct()).hasSize(1)
        assertThat(eventCount(fixture)).isEqualTo(2)
    }

    @Test
    fun `stored timestamp precision is identical on first receipt replay and projection`() {
        val fixture = seed()
        val fact =
            RunSupervisorHandover(
                fixture.run,
                fixture.initial.assignment.id,
                UUID.randomUUID(),
                RunSupervisionRevision(1),
                fixture.outgoing,
                fixture.incoming,
                fixture.outgoingEvidence,
                fixture.incomingEvidence,
                NOW.plusNanos(123456789),
            )
        val stored =
            transactions.required {
                check(assignments.lockRun(fixture.tenant, fixture.run))
                handovers.lockCommand(fixture.tenant, fact.commandId)
                handovers.record(fixture.tenant, fact)
            }
        assertThat(handovers.findByCommandId(fixture.tenant, fact.commandId)).isEqualTo(stored)
        val current = assignments.findAssigned(fixture.tenant, fixture.run, fixture.incoming, NOW)!!
        assertThat(current.supervisedAt).isEqualTo(stored.handover.occurredAt)
        assertThat(stored.handover.occurredAt.nano % 1000).isZero()
    }

    @Test
    fun `malformed browser inputs return stable private-safe problems without changing ownership`() {
        val fixture = seed()
        for (query in listOf("", "?expectedSupervisionRevision=invalid", "?expectedSupervisionRevision=-1")) {
            mvc
                .perform(get(path(fixture, "handover-candidates") + query).with(session(fixture.outgoing)))
                .andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-run-supervisor-handover"))
        }
        mvc
            .perform(
                post(path(fixture, "supervisor-handovers"))
                    .with(session(fixture.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"commandId\":\"not-a-uuid\"}"),
            ).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.detail").value("Invalid supervisor handover request"))
        assertThat(eventCount(fixture)).isEqualTo(1)
    }

    @Test
    fun `cross-tenant caller and changed own command intent cannot transfer supervision`() {
        val fixture = seed()
        val foreign = seed()
        mvc
            .perform(
                get(path(fixture, "handover-candidates") + "?expectedSupervisionRevision=0")
                    .with(session(foreign.outgoing)),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path(fixture, "supervisor-handovers"))
                    .with(session(foreign.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command(fixture))),
            ).andExpect(status().isForbidden)
        val command = command(fixture)
        service.handover(command)
        mvc
            .perform(
                post(path(fixture, "supervisor-handovers"))
                    .with(session(fixture.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command.copy(supervisorActorId = fixture.other.value))),
            ).andExpect(status().isConflict)
        assertThat(eventCount(fixture)).isEqualTo(2)
        assertThat(eventCount(foreign)).isEqualTo(1)
    }

    @Test
    fun `projection mismatch rolls back the attempted audit append and events remain immutable`() {
        val fixture = seed()
        val invalid =
            RunSupervisorHandover(
                fixture.run,
                fixture.initial.assignment.id,
                UUID.randomUUID(),
                RunSupervisionRevision(2),
                fixture.outgoing,
                fixture.incoming,
                fixture.outgoingEvidence,
                fixture.incomingEvidence,
                NOW,
            )
        assertThatThrownBy {
            transactions.required {
                check(assignments.lockRun(fixture.tenant, fixture.run))
                handovers.record(fixture.tenant, invalid)
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(eventCount(fixture)).isEqualTo(1)
        assertThat(
            console.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).supervisionRevision.value,
        ).isZero()
        assertThatThrownBy {
            transactions.required {
                jdbc
                    .sql("DELETE FROM resolution_run_supervision_events WHERE tenant_id = :tenant")
                    .param("tenant", fixture.tenant.value)
                    .update()
            }
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun seed() = transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, NOW) }

    private fun eventCount(fixture: RunSupervisionDatabaseFixture): Int =
        jdbc
            .sql(
                "SELECT COUNT(*) FROM resolution_run_supervision_events WHERE tenant_id = :tenant AND run_id = :run",
            ).param("tenant", fixture.tenant.value)
            .param("run", fixture.run.value)
            .query(Int::class.java)
            .single()

    private fun command(fixture: RunSupervisionDatabaseFixture) =
        HandoverRunSupervisorCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            fixture.incoming.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
        )

    private fun path(
        fixture: RunSupervisionDatabaseFixture,
        suffix: String,
    ): String = "/bff/v1/tenants/${fixture.tenant.value}/resolution-runs/${fixture.run.value}/$suffix"

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("resolver-${actor.value}")
        }

    private fun body(command: HandoverRunSupervisorCommand): String =
        """
        {
            "commandId":"${command.commandId}",
            "expectedSupervisionRevision":${command.expectedRevision.value},
            "supervisorActorId":"${command.supervisorActorId}"
        }
        """.trimIndent()

    private fun compete(
        first: HandoverRunSupervisorCommand,
        second: HandoverRunSupervisorCommand,
    ) = Executors.newFixedThreadPool(2).use { executor ->
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val tasks =
            listOf(first, second).map { command ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        runCatching { service.handover(command) }
                    },
                )
            }
        try {
            check(ready.await(10, TimeUnit.SECONDS))
        } finally {
            start.countDown()
        }
        tasks.map { it.get(20, TimeUnit.SECONDS) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean
        @Primary
        fun handoverTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-14T07:00:00Z")

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
