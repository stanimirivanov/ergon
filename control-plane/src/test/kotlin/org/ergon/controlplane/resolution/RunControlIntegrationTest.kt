package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture.session
import org.ergon.controlplane.resolution.RunEscalationTestFixture.together
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CancelSupervisedRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunCancellationRecords
import org.ergon.controlplane.resolution.application.RunCancellationService
import org.ergon.controlplane.resolution.application.RunCommandControl
import org.ergon.controlplane.resolution.application.RunCommandControlGate
import org.ergon.controlplane.resolution.application.RunControlAction
import org.ergon.controlplane.resolution.application.RunControlAdmission
import org.ergon.controlplane.resolution.application.RunControlCommand
import org.ergon.controlplane.resolution.application.RunControlCommandKey
import org.ergon.controlplane.resolution.application.RunControlCommandKind
import org.ergon.controlplane.resolution.application.RunControlConflictException
import org.ergon.controlplane.resolution.application.RunControlDisabledException
import org.ergon.controlplane.resolution.application.RunControlIdentityGenerator
import org.ergon.controlplane.resolution.application.RunControlRepository
import org.ergon.controlplane.resolution.application.RunControlRequiredException
import org.ergon.controlplane.resolution.application.RunControlService
import org.ergon.controlplane.resolution.application.RunControlStatus
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
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
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.run-supervision.control.enabled=true",
        "ergon.run-supervision.cancellation.enabled=true", "ergon.run-supervision.handover.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
// This context owns one explicitly advanced clock; tests drive concurrency explicitly.
@Execution(ExecutionMode.SAME_THREAD)
@Import(BrowserSessionTestClientConfiguration::class, RunControlIntegrationTest.TimeConfiguration::class)
@Suppress("LongParameterList") // Actual HTTP, authority, PostgreSQL locks and rollback are the acceptance boundary.
class RunControlIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val controls: RunControlService,
    @Autowired private val repository: RunControlRepository,
    @Autowired private val cancellations: RunCancellationService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val records: RunCancellationRecords,
    @Autowired private val identities: ResolutionRunEventIdentityGenerator,
    @Autowired private val gate: RunCommandControlGate,
    @Autowired private val clock: MutableRunControlClock,
) {
    @BeforeEach
    fun resetTime() {
        clock.set(NOW)
    }

    @Test
    fun `two sessions cannot acquire the same run and identical replay never transfers control`() {
        val fixture = seed()
        val holders = listOf(UUID.randomUUID(), UUID.randomUUID())
        val attempts = holders.map { holder -> { runCatching { controls.change(acquisition(fixture), holder) } } }
        val results = together(attempts[0], attempts[1])
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(RunControlConflictException::class.java)
        val original = results.single { it.isSuccess }.getOrThrow()
        val other = holders.single { it != original.holderId }
        assertThat(controls.change(original.command, other)).isEqualTo(original)
        val observed = controls.status(fixture.tenant.value, fixture.run.value, fixture.outgoing.value, other)
        assertThat(observed.status).isEqualTo(RunControlStatus.ANOTHER_SESSION)
        assertThat(observed.fence).isNull()
        assertThat(count("resolution_run_control_events", fixture)).isEqualTo(1)
    }

    @Test
    fun `renewal is explicit release is holder only and reacquisition advances the fence`() {
        val fixture = seed()
        val lease = acquire(fixture)
        val other = UUID.randomUUID()
        val renewal =
            lease.command.copy(
                commandId = UUID.randomUUID(),
                action = RunControlAction.RENEW,
                expectedFence = lease.fence,
            )
        assertThatThrownBy { controls.change(renewal, other) }.isInstanceOf(RunControlConflictException::class.java)
        clock.set(NOW.plusSeconds(60))
        val renewed = controls.change(renewal, lease.holderId)
        assertThat(renewed.fence).isEqualTo(lease.fence)
        assertThat(renewed.expiresAt).isEqualTo(NOW.plusSeconds(180))
        clock.set(NOW.plusSeconds(61))
        assertThat(controls.change(renewal, lease.holderId)).isEqualTo(renewed)
        val release = renewal.copy(commandId = UUID.randomUUID(), action = RunControlAction.RELEASE)
        assertThatThrownBy { controls.change(release, other) }.isInstanceOf(RunControlConflictException::class.java)
        controls.change(release, lease.holderId)
        assertThatThrownBy { cancellations.cancel(cancel(fixture), RunCommandControl(lease.fence, lease.holderId)) }
            .isInstanceOf(RunControlConflictException::class.java)
        val next = acquire(fixture, other)
        assertThat(next.fence.revision).isEqualTo(lease.fence.revision + 1)
        assertThat(next.fence.leaseId).isNotEqualTo(lease.fence.leaseId)
        assertThat(count("resolution_run_cancellation_commands", fixture)).isZero()
    }

    @Test
    fun `exclusive expiry fences uncertain intent while terminal replay remains historical`() {
        val fixture = seed()
        val original = acquire(fixture)
        val command = cancel(fixture)
        val oldControl = RunCommandControl(original.fence, original.holderId)
        clock.set(original.expiresAt)
        assertThat(controls.status(command.tenantId, command.runId, command.actorId, original.holderId).status)
            .isEqualTo(RunControlStatus.EXPIRED)
        assertThatThrownBy { cancellations.cancel(command, oldControl) }
            .isInstanceOf(RunControlConflictException::class.java)
        val lease = acquire(fixture, original.holderId)
        assertThatThrownBy { cancellations.cancel(command, oldControl) }
            .isInstanceOf(RunControlConflictException::class.java)
        val currentControl = RunCommandControl(lease.fence, lease.holderId)
        val newIntent = command.copy(commandId = UUID.randomUUID())
        val first = cancellations.cancel(newIntent, currentControl).stored
        assertThat(controls.status(command.tenantId, command.runId, command.actorId, lease.holderId).status)
            .isEqualTo(RunControlStatus.TERMINAL)
        clock.set(lease.expiresAt)
        assertThat(cancellations.cancel(newIntent, currentControl.copy(holderId = UUID.randomUUID())).stored)
            .isEqualTo(first)
        assertThatThrownBy { cancellations.cancel(newIntent, oldControl) }
            .isInstanceOf(RunControlConflictException::class.java)
        assertThatThrownBy { controls.change(acquisition(fixture), lease.holderId) }
            .isInstanceOf(RunControlConflictException::class.java)
        assertThat(count("resolution_run_control_admissions", fixture)).isEqualTo(1)
    }

    @Test
    fun `handover invalidates the epoch without granting incoming control or restoring control on replay`() {
        val fixture = seed()
        val lease = acquire(fixture)
        val context = RunCommandControl(lease.fence, lease.holderId)
        val command =
            HandoverRunSupervisorCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            )
        assertThatThrownBy { handovers.handover(command) }.isInstanceOf(RunControlRequiredException::class.java)
        val first = handovers.handover(command, context).stored
        assertThat(controls.status(command.tenantId, command.runId, fixture.incoming.value, UUID.randomUUID()).status)
            .isEqualTo(RunControlStatus.AVAILABLE)
        assertThatThrownBy { controls.status(command.tenantId, command.runId, command.actorId, lease.holderId) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        val incoming =
            controls.change(
                acquisition(fixture).copy(
                    actorId = fixture.incoming.value,
                    supervisionRevision = RunSupervisionRevision(1),
                ),
                UUID.randomUUID(),
            )
        assertThat(incoming.fence.revision).isEqualTo(2)
        assertThat(handovers.handover(command, context).stored).isEqualTo(first)
        assertThat(repository.current(fixture.tenant, fixture.run)).isEqualTo(incoming)
    }

    @Test
    fun `expiry during a real state lock wait rolls back provisional cancellation and its receipt`() {
        val fixture = seed()
        val lease = acquire(fixture)
        val reachedStateLock = CountDownLatch(1)
        val transitions =
            object : ResolutionRunTransitionRepository by records.transitions {
                override fun lockState(
                    tenantId: TenantId,
                    runId: ResolutionRunId,
                ): ResolutionRunStateSnapshot? {
                    reachedStateLock.countDown()
                    return records.transitions.lockState(tenantId, runId)
                }
            }
        val delayed =
            RunCancellationService(
                records.copy(transitions = transitions),
                identities,
                transactions,
                clock,
                true,
                gate,
            )
        Executors.newSingleThreadExecutor().use { pool ->
            val waiting =
                transactions.required {
                    records.transitions.lockState(fixture.tenant, fixture.run)
                    val future =
                        pool.submit<Throwable?> {
                            runCatching {
                                delayed.cancel(
                                    cancel(fixture),
                                    RunCommandControl(lease.fence, lease.holderId),
                                )
                            }.exceptionOrNull()
                        }
                    check(reachedStateLock.await(15, TimeUnit.SECONDS))
                    clock.set(lease.expiresAt)
                    future
                }
            assertThat(waiting.get(30, TimeUnit.SECONDS)).isInstanceOf(RunControlConflictException::class.java)
        }
        assertThat(count("resolution_run_events", fixture)).isZero()
        assertThat(count("resolution_run_cancellation_commands", fixture)).isZero()
        assertThat(count("resolution_run_control_admissions", fixture)).isZero()
    }

    @Test
    fun `HTTP requires session CSRF and exact server binding and never discloses another session fence`() {
        val fixture = seed()
        val browser = MockHttpSession()
        val other = MockHttpSession()
        val base = base(fixture)
        val command = acquisition(fixture)
        val body = """{"commandId":"${command.commandId}","expectedSupervisionRevision":0,"action":"ACQUIRE"}"""
        mvc.perform(get("$base/control")).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post("$base/control-commands")
                    .session(browser)
                    .with(session(command.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post("$base/control-commands")
                    .session(browser)
                    .with(session(command.actorId))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.holderId").doesNotExist())
            .andExpect(jsonPath("$.actorId").doesNotExist())
        val lease = checkNotNull(repository.current(fixture.tenant, fixture.run))
        mvc
            .perform(get("$base/control").session(browser).with(session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("THIS_SESSION"))
            .andExpect(jsonPath("$.fence.leaseId").value(lease.fence.leaseId.toString()))
        mvc
            .perform(get("$base/control").session(other).with(session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ANOTHER_SESSION"))
            .andExpect(jsonPath("$.fence").doesNotExist())
            .andExpect(jsonPath("$.holderId").doesNotExist())
        val cancelBody = """{"commandId":"${UUID.randomUUID()}","expectedSupervisionRevision":0,"expectedRunVersion":0,
            "control":{"leaseId":"${lease.fence.leaseId}","revision":1}}"""
        sendCancellation(fixture, other, cancelBody)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-control-conflict"))
        sendCancellation(fixture, browser, cancelBody).andExpect(status().isCreated)
        sendCancellation(fixture, other, cancelBody).andExpect(status().isOk)
    }

    @Test
    fun `tenant and actor gates remain neutral and history is immutable`() {
        val fixture = seed()
        val lease = acquire(fixture)
        val foreignKey =
            RunControlCommandKey(
                UUID.randomUUID(),
                fixture.run.value,
                RunControlCommandKind.CANCELLATION,
                UUID.randomUUID(),
            )
        assertThatThrownBy {
            transactions.required { repository.recordAdmission(RunControlAdmission(foreignKey, lease, NOW)) }
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            controls.status(fixture.tenant.value, fixture.run.value, fixture.incoming.value, lease.holderId)
        }.isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy {
            controls.status(UUID.randomUUID(), fixture.run.value, fixture.outgoing.value, lease.holderId)
        }.isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy {
            controls.change(lease.command.copy(supervisionRevision = RunSupervisionRevision(1)), lease.holderId)
        }.isInstanceOf(RunControlConflictException::class.java)
        assertThatThrownBy {
            jdbc
                .sql("UPDATE resolution_run_control_events SET holder_id = :holder WHERE tenant_id = :tenant")
                .param("holder", UUID.randomUUID())
                .param("tenant", fixture.tenant.value)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        cancellations.cancel(cancel(fixture), RunCommandControl(lease.fence, lease.holderId))
        assertThatThrownBy {
            jdbc
                .sql("DELETE FROM resolution_run_control_admissions WHERE tenant_id = :tenant")
                .param("tenant", fixture.tenant.value)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        clock.set(NOW.plusSeconds(3600))
        assertThatThrownBy { controls.change(lease.command, lease.holderId) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    @Test
    fun `enabling protection preserves unfenced legacy acknowledgements without applying effects again`() {
        val fixture = seed()
        val command = cancel(fixture)
        val legacyGate = RunCommandControlGate(repository, records.authorities, clock, false)
        val legacy = RunCancellationService(records, identities, transactions, clock, true, legacyGate)
        val stored = legacy.cancel(command).stored
        assertThat(cancellations.cancel(command).stored).isEqualTo(stored)
        assertThat(count("resolution_run_control_admissions", fixture)).isZero()
        assertThat(count("resolution_run_cancellation_commands", fixture)).isEqualTo(1)
    }

    @Test
    fun `disabling admission protection preserves exact lifecycle history without extending control`() {
        val fixture = seed()
        val lease = acquire(fixture)
        val disabled =
            RunControlService(
                records.assignments,
                records.authorities,
                records.transitions,
                repository,
                transactions,
                clock,
                RunControlIdentityGenerator { UUID.randomUUID() },
                false,
            )
        clock.set(lease.expiresAt)
        assertThat(disabled.change(lease.command, UUID.randomUUID())).isEqualTo(lease)
        val status = disabled.status(fixture.tenant.value, fixture.run.value, fixture.outgoing.value, lease.holderId)
        assertThat(status.status)
            .isEqualTo(RunControlStatus.DISABLED)
        assertThatThrownBy { disabled.change(acquisition(fixture), lease.holderId) }
            .isInstanceOf(RunControlDisabledException::class.java)
        assertThat(repository.current(fixture.tenant, fixture.run)).isEqualTo(lease)
    }

    private fun sendCancellation(
        fixture: RunSupervisionDatabaseFixture,
        browser: MockHttpSession,
        body: String,
    ) = mvc.perform(
        post(base(fixture) + "/cancellation-commands")
            .session(browser)
            .with(session(fixture.outgoing.value))
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun seed() = transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, NOW) }

    private fun acquisition(fixture: RunSupervisionDatabaseFixture) =
        RunControlCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            RunControlAction.ACQUIRE,
            null,
        )

    private fun acquire(
        fixture: RunSupervisionDatabaseFixture,
        holder: UUID = UUID.randomUUID(),
    ) = controls.change(acquisition(fixture), holder)

    private fun cancel(fixture: RunSupervisionDatabaseFixture) =
        CancelSupervisedRunCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            0,
        )

    private fun base(fixture: RunSupervisionDatabaseFixture) =
        "/bff/v1/tenants/${fixture.tenant.value}/resolution-runs/${fixture.run.value}"

    private fun count(
        table: String,
        fixture: RunSupervisionDatabaseFixture,
    ) = jdbc
        .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
        .param("tenant", fixture.tenant.value)
        .query(Int::class.java)
        .single()

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfiguration {
        @Bean @Primary
        fun runControlClock() = MutableRunControlClock(NOW)
    }

    companion object {
        val NOW: Instant = RunRetryTestFixture.NOW

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

/** Deterministic elapsed lock time; no wall-clock sleeps or production lease-duration overrides. */
class MutableRunControlClock(
    initial: Instant,
) : Clock() {
    private val now = AtomicReference(initial)

    fun set(instant: Instant) {
        now.set(instant)
    }

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock {
        require(zone == ZoneOffset.UTC)
        return this
    }
}
