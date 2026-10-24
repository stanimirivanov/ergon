package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionCommand
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionService
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverCommand
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverService
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityCommand
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityRepository
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityService
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityUnavailableException
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpPriority
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.follow-up.priority-enabled=true",
        "ergon.follow-up.completion-enabled=true", "ergon.follow-up.handover-enabled=true",
        "ergon.follow-up.release-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, PriorityClockConfiguration::class)
@Suppress("LongParameterList") // Real security, authority, ownership and transaction boundaries are the test subject.
class HumanFollowUpPriorityIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val priorities: HumanFollowUpPriorityService,
    @Autowired private val repository: HumanFollowUpPriorityRepository,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val claimRepository: HumanFollowUpClaimRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val runner: TransactionRunner,
    @Autowired private val handovers: HumanFollowUpHandoverService,
    @Autowired private val completions: HumanFollowUpCompletionService,
    @Autowired private val releases: HumanFollowUpReleaseService,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

    @Test
    fun `owned reads expose the default and latest pair rather than a replayed historical receipt`() {
        val unassigned = fixture()
        retireWork(unassigned, "release")
        mvc
            .perform(readWork(unassigned.command))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].priority").value("NORMAL"))
            .andExpect(jsonPath("$.items[0].priorityRevision").value(0))
        val c = fixture().command
        mvc
            .perform(readWork(c, owned = true))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].workItem.priority").value("NORMAL"))
            .andExpect(jsonPath("$.items[0].workItem.priorityRevision").value(0))
        priorities.assign(c)
        val next =
            c.copy(
                commandId = UUID.randomUUID(),
                expectedPriorityRevision = 1,
                priority = HumanFollowUpPriority.LOW,
            )
        priorities.assign(next)
        priorities.assign(next.copy(commandId = UUID.randomUUID(), expectedPriorityRevision = 2))
        assertThat(
            priorities
                .assign(c)
                .receipt.command.priority,
        ).isEqualTo(HumanFollowUpPriority.HIGH)
        val response =
            mvc
                .perform(readWork(c, owned = true))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.items[0].workItem.priority").value("LOW"))
                .andExpect(jsonPath("$.items[0].workItem.priorityRevision").value(3))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain("commandId", "authorityEvidenceId", "assignedAt")
    }

    @Test
    fun `metadata survives handover and release without restoring former owner or expired authority access`() {
        val f = fixture()
        val c = f.command
        priorities.assign(c)
        retireWork(f, "handover")
        mvc.perform(readWork(c, owned = true)).andExpect(jsonPath("$.items").isEmpty)
        mvc
            .perform(
                get("/bff/v1/tenants/${c.tenantId}/human-follow-ups/${c.workItemId}/case-summary")
                    .with(RunEscalationTestFixture.session(c.actorId)),
            ).andExpect(status().isNotFound)
        mvc
            .perform(readWork(c, owned = true, actor = f.graph.incoming.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].workItem.priority").value("HIGH"))
            .andExpect(jsonPath("$.items[0].workItem.priorityRevision").value(1))
        val successor =
            requireNotNull(
                claimRepository.findByWorkItem(
                    TenantId(c.tenantId),
                    HumanFollowUpWorkItemId(c.workItemId),
                ),
            )
        releases.release(
            HumanFollowUpReleaseCommand(
                c.tenantId,
                c.workItemId,
                successor.claim.id.value,
                f.graph.incoming.value,
                3,
            ),
        )
        mvc
            .perform(readWork(c))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].priority").value("HIGH"))
            .andExpect(jsonPath("$.items[0].priorityRevision").value(1))
        mvc
            .perform(readWork(c, actor = f.graph.expired.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
    }

    @Test
    fun `completed work discloses no active priority metadata`() {
        val f = fixture()
        priorities.assign(f.command)
        retireWork(f, "completion")
        mvc.perform(readWork(f.command)).andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
        mvc
            .perform(readWork(f.command, owned = true))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        assertThat(
            claimRepository.findOwnedWorkForResolver(
                f.graph.tenant,
                HumanFollowUpWorkItemId(f.command.workItemId),
                f.graph.outgoing,
                NOW,
            ),
        ).isNull()
    }

    @Test
    fun `priority updates preserve owned cursors and shared oldest first pages with tenant isolated values`() {
        val first = fixture(work = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val c = first.command
        val second = fixture(first.graph.tenant, c.actorId, UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val third = fixture(first.graph.tenant, c.actorId, UUID.fromString("00000000-0000-0000-0000-000000000003"))
        val before = claims.listOwned(c.tenantId, c.actorId, 1, null, null)
        priorities.assign(c.copy(priority = HumanFollowUpPriority.LOW))
        priorities.assign(second.command.copy(priority = HumanFollowUpPriority.URGENT))
        priorities.assign(third.command)
        val after = claims.listOwned(c.tenantId, c.actorId, 1, null, null)
        assertThat(after.items.map { it.workItem.item.id }).isEqualTo(before.items.map { it.workItem.item.id })
        assertThat(after.nextCursor).isEqualTo(before.nextCursor)
        val cursor = requireNotNull(after.nextCursor)
        val remaining = claims.listOwned(c.tenantId, c.actorId, 100, cursor.claimedAt, cursor.claimId.value)
        assertThat(remaining.items).hasSize(2)
        for (f in listOf(first, second, third)) retireWork(f, "release")
        val foreign = fixture(work = c.workItemId).command
        priorities.assign(foreign)
        priorities.assign(
            foreign.copy(
                commandId = UUID.randomUUID(),
                expectedPriorityRevision = 1,
                priority = HumanFollowUpPriority.URGENT,
            ),
        )
        mvc
            .perform(readWork(c).param("limit", "1").param("queueKey", "access-restoration"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].workItemId").value(c.workItemId.toString()))
            .andExpect(jsonPath("$.items[0].priority").value("LOW"))
            .andExpect(jsonPath("$.items[0].priorityRevision").value(1))
            .andExpect(jsonPath("$.nextCursor.afterWorkItemId").value(c.workItemId.toString()))
            .andExpect(jsonPath("$.nextCursor.afterOpenedAt").value(NOW.toString()))
        mvc
            .perform(
                readWork(c)
                    .param("limit", "1")
                    .param("afterOpenedAt", NOW.toString())
                    .param("afterWorkItemId", c.workItemId.toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].workItemId").value(second.command.workItemId.toString()))
            .andExpect(jsonPath("$.items[0].priority").value("URGENT"))
        mvc
            .perform(readWork(c).param("queueKey", "unknown-queue"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
    }

    @Test
    fun `session and CSRF protect bounded immutable acknowledgement and historical replay`() {
        val c = fixture().command
        mvc
            .perform(post(path(c)).contentType(MediaType.APPLICATION_JSON).content(body(c)))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path(c))
                    .with(RunEscalationTestFixture.session(c.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(c)),
            ).andExpect(status().isForbidden)
        send(c.copy(actorId = UUID.randomUUID())).andExpect(status().isForbidden)
        val first =
            send(c)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.commandId").value(c.commandId.toString()))
                .andExpect(jsonPath("$.workItemId").value(c.workItemId.toString()))
                .andExpect(jsonPath("$.claimId").value(c.claimId.toString()))
                .andExpect(jsonPath("$.expectedOwnershipRevision").value(1))
                .andExpect(jsonPath("$.expectedPriorityRevision").value(0))
                .andExpect(jsonPath("$.priorityRevision").value(1))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.assignedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.recordedAt").isString)
                .andReturn()
                .response.contentAsString
        assertThat(first).doesNotContain("actorId", "authority", "caseId", "runId", "provider", "token")
        for ((index, value) in listOf("LOW", "NORMAL", "URGENT", "URGENT").withIndex()) {
            send(
                c.copy(
                    commandId = UUID.randomUUID(),
                    expectedPriorityRevision = index + 1L,
                    priority = HumanFollowUpPriority.valueOf(value),
                ),
            ).andExpect(status().isCreated)
        }
        assertThat(
            send(c)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        assertThat(repository.currentRevision(TenantId(c.tenantId), HumanFollowUpWorkItemId(c.workItemId)).value)
            .isEqualTo(5)
        assertThat(
            claimRepository
                .findByWorkItem(TenantId(c.tenantId), HumanFollowUpWorkItemId(c.workItemId))
                ?.claim
                ?.id
                ?.value,
        ).isEqualTo(c.claimId)
    }

    @Test
    fun `invalid input stale revisions changed intent and hidden work never overwrite priority`() {
        val f = fixture()
        val c = f.command
        for (invalid in listOf(
            c.copy(expectedPriorityRevision = -1),
            c.copy(expectedPriorityRevision = Long.MAX_VALUE),
            c.copy(expectedOwnershipRevision = 0),
            c.copy(expectedOwnershipRevision = -1),
        )) {
            send(invalid).andExpect(status().isBadRequest)
        }
        send(c, body(c).replace("HIGH", "CRITICAL"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-priority"))
        for (changed in listOf(
            c.copy(claimId = UUID.randomUUID()),
            c.copy(expectedOwnershipRevision = 3),
            c.copy(expectedPriorityRevision = 1),
        )) {
            send(changed)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-priority-conflict"))
        }
        for (hidden in listOf(
            c.copy(actorId = f.graph.incoming.value),
            c.copy(actorId = f.graph.expired.value),
            c.copy(workItemId = UUID.randomUUID()),
            c.copy(tenantId = UUID.randomUUID()),
        )) {
            assertThatThrownBy { priorities.assign(hidden) }
                .isInstanceOf(HumanFollowUpPriorityNotFoundException::class.java)
        }
        send(c).andExpect(status().isCreated)
        send(c.copy(priority = HumanFollowUpPriority.LOW)).andExpect(status().isConflict)
        send(c.copy(expectedPriorityRevision = 1)).andExpect(status().isConflict)
        send(c.copy(commandId = UUID.randomUUID())).andExpect(status().isConflict)
        assertThat(repository.find(c)?.revision?.value).isEqualTo(1)
        assertThatThrownBy { service(enabled = false).assign(c) }
            .isInstanceOf(HumanFollowUpPriorityUnavailableException::class.java)
    }

    @Test
    fun `replay retires after handover completion or authority expiry and is never available to a successor`() {
        for (terminal in listOf(false, true)) {
            val f = fixture()
            val c = f.command
            priorities.assign(c)
            assertThatThrownBy { service(clock = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC)).assign(c) }
                .isInstanceOf(HumanFollowUpPriorityNotFoundException::class.java)
            retireWork(f, if (terminal) "completion" else "handover")
            send(c)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-priority-not-found"))
            if (!terminal) {
                val successor =
                    requireNotNull(
                        claimRepository.findByWorkItem(TenantId(c.tenantId), HumanFollowUpWorkItemId(c.workItemId)),
                    )
                send(
                    c.copy(
                        actorId = f.graph.incoming.value,
                        claimId = successor.claim.id.value,
                        expectedOwnershipRevision = 3,
                    ),
                ).andExpect(status().isNotFound)
            }
            assertThat(repository.find(c)?.revision?.value).isEqualTo(1)
        }
        val released = fixture().command
        priorities.assign(released)
        releases.release(
            HumanFollowUpReleaseCommand(
                released.tenantId,
                released.workItemId,
                released.claimId,
                released.actorId,
                1,
            ),
        )
        send(released).andExpect(status().isNotFound)
    }

    @Test
    fun `rollback leaves no assignment and identical concurrent commands acknowledge one winner`() {
        val c = fixture().command
        assertThatThrownBy { service(enabled = false).assign(c) }
            .isInstanceOf(HumanFollowUpPriorityUnavailableException::class.java)
        transactions.executeWithoutResult { tx ->
            priorities.assign(c)
            tx.setRollbackOnly()
        }
        assertThat(repository.find(c)).isNull()
        val results = RunEscalationTestFixture.together({ priorities.assign(c) }, { priorities.assign(c) })
        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results[0].receipt).isEqualTo(results[1].receipt)
        val next = c.copy(commandId = UUID.randomUUID(), expectedPriorityRevision = 1)
        val competing =
            RunEscalationTestFixture.together(
                { runCatching { priorities.assign(next) }.exceptionOrNull() },
                { runCatching { priorities.assign(next.copy(commandId = UUID.randomUUID())) }.exceptionOrNull() },
            )
        assertThat(competing.count { it == null }).isEqualTo(1)
        assertThat(competing.filterNotNull().single()).isInstanceOf(HumanFollowUpPriorityConflictException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["expiry", "completion", "handover", "release", "priority"])
    fun `authority ownership completion and priority are rechecked after the actual work lock wait`(change: String) {
        val f = fixture()
        val c = f.command
        val reads = AtomicInteger()
        val pid = AtomicInteger()
        val expiredClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = Clock.fixed(NOW.plusSeconds(3600), zone)

                override fun instant(): Instant {
                    reads.incrementAndGet()
                    return if (change == "expiry") NOW.plusSeconds(3600) else NOW
                }
            }
        Executors.newSingleThreadExecutor().use { executor ->
            val pending =
                requireNotNull(
                    transactions.execute {
                        claimRepository.lockOpenWorkItem(TenantId(c.tenantId), HumanFollowUpWorkItemId(c.workItemId))
                        val future =
                            executor.submit<Throwable?> {
                                runCatching {
                                    runner.required {
                                        pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                                        service(clock = expiredClock).assign(c)
                                    }
                                }.exceptionOrNull()
                            }
                        await().atMost(20, TimeUnit.SECONDS).until {
                            pid.get() != 0 &&
                                jdbc
                                    .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                                    .param("pid", pid.get())
                                    .query(Boolean::class.java)
                                    .single()
                        }
                        assertThat(reads.get()).isZero()
                        when (change) {
                            "completion", "handover", "release" -> {
                                retireWork(f, change)
                            }

                            "priority" -> {
                                priorities.assign(c.copy(commandId = UUID.randomUUID()))
                            }
                        }
                        future
                    },
                )
            val expected =
                if (change == "priority") {
                    HumanFollowUpPriorityConflictException::class.java
                } else {
                    HumanFollowUpPriorityNotFoundException::class.java
                }
            assertThat(pending.get(20, TimeUnit.SECONDS)).isInstanceOf(expected)
        }
        assertThat(reads.get()).isEqualTo(if (change == "completion") 0 else 1)
        assertThat(repository.find(c)).isNull()
    }

    private fun service(
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        enabled: Boolean = true,
    ) = HumanFollowUpPriorityService(claimRepository, repository, authorities, runner, clock, enabled)

    private fun retireWork(
        f: Owned,
        operation: String,
    ) {
        val c = f.command
        when (operation) {
            "completion" -> {
                completions.complete(
                    HumanFollowUpCompletionCommand(
                        c.tenantId,
                        c.workItemId,
                        c.actorId,
                        UUID.randomUUID(),
                        c.claimId,
                        1,
                    ),
                )
            }

            "handover" -> {
                handovers.handOver(
                    HumanFollowUpHandoverCommand(
                        c.tenantId,
                        c.workItemId,
                        c.actorId,
                        UUID.randomUUID(),
                        c.claimId,
                        1,
                        f.graph.incoming.value,
                    ),
                )
            }

            "release" -> {
                releases.release(
                    HumanFollowUpReleaseCommand(c.tenantId, c.workItemId, c.claimId, c.actorId, 1),
                )
            }

            else -> {
                error("unsupported lifecycle change")
            }
        }
    }

    private fun fixture(
        tenant: TenantId = TenantId(UUID.randomUUID()),
        owner: UUID? = null,
        work: UUID = UUID.randomUUID(),
    ): Owned {
        val graph =
            requireNotNull(
                transactions.execute {
                    RunSupervisionDatabaseFixture.seed(jdbc, NOW, tenant = tenant)
                },
            )
        val actor = owner ?: graph.outgoing.value
        transactions.executeWithoutResult {
            // Isolate only the upstream escalation source; claim and priority constraints stay enabled.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items
                        (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                    VALUES (:tenant, :work, :run, :event, 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :at)
                    """.trimIndent(),
                ).param("tenant", graph.tenant.value)
                .param("work", work)
                .param("run", graph.run.value)
                .param("event", UUID.randomUUID())
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update()
            jdbc.sql("SET LOCAL session_replication_role = origin").update()
        }
        val claim = claims.claim(graph.tenant.value, work, actor).storedClaim.claim
        return Owned(
            HumanFollowUpPriorityCommand(
                graph.tenant.value,
                work,
                actor,
                UUID.randomUUID(),
                claim.id.value,
                1,
                0,
                HumanFollowUpPriority.HIGH,
            ),
            graph,
        )
    }

    private fun send(
        c: HumanFollowUpPriorityCommand,
        payload: String = body(c),
    ) = mvc.perform(
        post(path(c))
            .with(RunEscalationTestFixture.session(c.actorId))
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload),
    )

    private fun path(c: HumanFollowUpPriorityCommand) =
        "/bff/v1/tenants/${c.tenantId}/human-follow-ups/${c.workItemId}/priority-assignments"

    private fun readWork(
        c: HumanFollowUpPriorityCommand,
        owned: Boolean = false,
        actor: UUID = c.actorId,
    ) = get("/bff/v1/tenants/${c.tenantId}/human-follow-ups" + if (owned) "/owned" else "")
        .with(RunEscalationTestFixture.session(actor))

    private fun body(c: HumanFollowUpPriorityCommand) =
        """{"commandId":"${c.commandId}","claimId":"${c.claimId}",
            "expectedOwnershipRevision":${c.expectedOwnershipRevision},
            "expectedPriorityRevision":${c.expectedPriorityRevision},"priority":"${c.priority}"}"""

    private data class Owned(
        val command: HumanFollowUpPriorityCommand,
        val graph: RunSupervisionDatabaseFixture,
    )

    companion object {
        val NOW: Instant = Instant.parse("2026-10-24T10:00:00Z")

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

@TestConfiguration(proxyBeanMethods = false)
private class PriorityClockConfiguration {
    @Bean @Primary
    fun priorityClock(): Clock = Clock.fixed(HumanFollowUpPriorityIntegrationTest.NOW, ZoneOffset.UTC)
}
