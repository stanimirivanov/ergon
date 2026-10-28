package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverCommand
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverRecipientUnavailableException
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverRecording
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverRepository
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverService
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverUnavailableException
import org.ergon.controlplane.followup.application.HumanFollowUpNoteCommand
import org.ergon.controlplane.followup.application.HumanFollowUpNoteService
import org.ergon.controlplane.followup.application.HumanFollowUpNotesNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.TenantId
import org.flywaydb.core.Flyway
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
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.follow-up.handover-enabled=true",
        "ergon.follow-up.release-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, HandoverClockConfiguration::class)
@Suppress("LongParameterList") // Real HTTP, authority, ownership and transaction boundaries are tested together.
class HumanFollowUpHandoverIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val handovers: HumanFollowUpHandoverService,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val notes: HumanFollowUpNoteService,
    @Autowired private val releases: HumanFollowUpReleaseService,
    @Autowired private val claimRepository: HumanFollowUpClaimRepository,
    @Autowired private val handoverRepository: HumanFollowUpHandoverRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val identities: HumanFollowUpClaimIdentityGenerator,
    @Autowired private val transactionRunner: TransactionRunner,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

    @Test
    fun `browser authenticates tenant actor enforces CSRF and returns identical sanitized replay`() {
        val command = fixture()
        mvc
            .perform(post(path(command)).contentType(MediaType.APPLICATION_JSON).content(body(command)))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path(command))
                    .with(RunEscalationTestFixture.session(command.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        mvc
            .perform(
                post(path(command))
                    .with(RunEscalationTestFixture.session(UUID.randomUUID()))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
        val first =
            post(command)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.ownershipRevision").value(3))
                .andExpect(jsonPath("$.recipientActorId").value(command.recipientActorId.toString()))
                .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
                .andExpect(jsonPath("$.senderAuthorityEvidenceId").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertThat(
            post(command)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        assertThat(eventCount(command)).isEqualTo(2)
        assertThat(notificationCount(command)).isEqualTo(1)
        assertThat(notificationCount(command.copy(recipientActorId = command.actorId))).isZero()
    }

    @Test
    fun `recipient inherits notes former owner loses private access and shared inbox never gains the item`() {
        val command = fixture()
        val note =
            notes
                .append(
                    HumanFollowUpNoteCommand(
                        command.tenantId,
                        command.workItemId,
                        command.actorId,
                        UUID.randomUUID(),
                        command.claimId,
                        1,
                        "Internal note",
                    ),
                ).stored
        val receipt = handovers.handOver(command).receipt
        assertThat(claims.listOwned(command.tenantId, command.actorId, 50, null, null).items).isEmpty()
        val incoming = claims.listOwned(command.tenantId, command.recipientActorId, 50, null, null).items.single()
        assertThat(incoming.claim.claim.id).isEqualTo(receipt.successorClaimId)
        assertThat(incoming.ownershipRevision.value).isEqualTo(3)
        assertThat(notes.list(command.tenantId, command.workItemId, command.recipientActorId).items)
            .containsExactly(note)
        assertThatThrownBy { notes.list(command.tenantId, command.workItemId, command.actorId) }
            .isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        mvc
            .perform(
                get("/bff/v1/tenants/${command.tenantId}/human-follow-ups/${command.workItemId}/case-summary")
                    .with(RunEscalationTestFixture.session(command.actorId)),
            ).andExpect(status().isNotFound)
        mvc
            .perform(
                get("/bff/v1/tenants/${command.tenantId}/human-follow-ups")
                    .with(RunEscalationTestFixture.session(command.actorId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        assertThat(
            jdbc
                .sql(
                    """
                    SELECT COUNT(*) FROM human_follow_up_ownership_events
                    WHERE tenant_id = :tenant AND event_type = 'RELEASED'
                    """.trimIndent(),
                ).param("tenant", command.tenantId)
                .query(Long::class.java)
                .single(),
        ).isZero()
    }

    @Test
    fun `changed intent conflicts and another sender cannot recover a receipt`() {
        val command = fixture()
        handovers.handOver(command)
        listOf(
            command.copy(recipientActorId = UUID.randomUUID()),
            command.copy(claimId = UUID.randomUUID()),
            command.copy(expectedOwnershipRevision = 3),
        ).forEach { changed ->
            assertThatThrownBy { handovers.handOver(changed) }
                .isInstanceOf(HumanFollowUpHandoverConflictException::class.java)
        }
        val impostor = command.copy(actorId = command.recipientActorId, recipientActorId = command.actorId)
        assertThatThrownBy { handovers.handOver(impostor) }
            .isInstanceOf(HumanFollowUpHandoverNotFoundException::class.java)
        assertThatThrownBy { handovers.handOver(command.copy(commandId = UUID.randomUUID())) }
            .isInstanceOf(HumanFollowUpHandoverNotFoundException::class.java)
    }

    @Test
    fun `stale state invalid revisions and self transfer never mutate ownership`() {
        val command = fixture()
        post(command.copy(expectedOwnershipRevision = 3)).andExpect(status().isConflict)
        post(command.copy(claimId = UUID.randomUUID())).andExpect(status().isConflict)
        listOf(0L, -1L, Long.MAX_VALUE).forEach { revision ->
            post(command.copy(expectedOwnershipRevision = revision)).andExpect(status().isBadRequest)
        }
        post(command.copy(recipientActorId = command.actorId)).andExpect(status().isBadRequest)
        assertThat(eventCount(command)).isEqualTo(1)
    }

    @Test
    fun `ineligible recipient coordinates are indistinguishable and expired sender cannot replay`() {
        val command = fixture()
        val expired =
            jdbc
                .sql("SELECT actor_id FROM approval_authority_evidence WHERE tenant_id = :tenant AND expires_at < :at")
                .param("tenant", command.tenantId)
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .query(UUID::class.java)
                .single()
        val foreign = fixture().recipientActorId
        listOf(UUID.randomUUID(), expired, foreign).forEach { recipient ->
            assertThatThrownBy { handovers.handOver(command.copy(recipientActorId = recipient)) }
                .isInstanceOf(HumanFollowUpHandoverRecipientUnavailableException::class.java)
        }
        assertThatThrownBy { handovers.handOver(command.copy(tenantId = UUID.randomUUID())) }
            .isInstanceOf(HumanFollowUpHandoverNotFoundException::class.java)
        handovers.handOver(command)
        // Evidence is immutable. Advance this use case's clock instead of rewriting expiry.
        val lateService = serviceAt(NOW.plusSeconds(3600))
        assertThatThrownBy { lateService.handOver(command) }
            .isInstanceOf(HumanFollowUpHandoverNotFoundException::class.java)
    }

    @Test
    fun `rollback after projection replacement leaves neither successor nor receipt`() {
        val command = fixture()
        transactions.executeWithoutResult { transaction ->
            handovers.handOver(command)
            transaction.setRollbackOnly()
        }
        assertThat(eventCount(command)).isEqualTo(1)
        assertThat(handovers.handOver(command).created).isTrue()
    }

    @Test
    fun `uncommitted transfer exposes old owner not an intermediate unowned projection`() {
        val command = fixture()
        transactions.executeWithoutResult {
            handovers.handOver(command)
            Executors.newSingleThreadExecutor().use { executor ->
                val visible =
                    executor
                        .submit<UUID> {
                            jdbc
                                .sql(
                                    """
                                    SELECT current_resolver_actor_id FROM human_follow_up_current_ownership
                                    WHERE tenant_id = :tenant AND work_item_id = :work
                                    """.trimIndent(),
                                ).param("tenant", command.tenantId)
                                .param("work", command.workItemId)
                                .query(UUID::class.java)
                                .single()
                        }.get(10, TimeUnit.SECONDS)
                assertThat(visible).isEqualTo(command.actorId)
            }
        }
        assertThat(claims.listOwned(command.tenantId, command.recipientActorId, 50, null, null).items).hasSize(1)
    }

    @Test
    fun `competing handover intents serialize and exactly one wins`() {
        val command = fixture()
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                listOf(command, command.copy(commandId = UUID.randomUUID()))
                    .map { intent ->
                        executor.submit<Throwable?> {
                            barrier.await(10, TimeUnit.SECONDS)
                            runCatching { handovers.handOver(intent) }.exceptionOrNull()
                        }
                    }.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(results.filterNotNull().single())
                .isInstanceOf(HumanFollowUpHandoverNotFoundException::class.java)
        }
        assertThat(eventCount(command)).isEqualTo(2)
    }

    @Test
    fun `handover acknowledgement survives recipient release without releasing its successor again`() {
        val command = fixture()
        val first = handovers.handOver(command).receipt
        releases.release(
            HumanFollowUpReleaseCommand(
                command.tenantId,
                command.workItemId,
                first.successorClaimId.value,
                command.recipientActorId,
                3,
            ),
        )
        assertThat(handovers.handOver(command).receipt).isEqualTo(first)
        assertThat(eventCount(command)).isEqualTo(3)
        jdbc
            .sql(
                """
                INSERT INTO approval_authority_evidence (
                    tenant_id, evidence_id, actor_id, authority, case_id, source_provider, source_reference, attested_at, expires_at
                )
                SELECT tenant_id, :evidence, actor_id, authority, case_id, source_provider, :reference, attested_at, :expiry
                FROM approval_authority_evidence WHERE tenant_id = :tenant AND actor_id = :sender
                """.trimIndent(),
            ).param("evidence", UUID.randomUUID())
            .param("reference", UUID.randomUUID().toString())
            .param("tenant", command.tenantId)
            .param("sender", command.actorId)
            .param("expiry", NOW.plusSeconds(7200).atOffset(ZoneOffset.UTC))
            .update()
        assertThat(serviceAt(NOW.plusSeconds(3600)).handOver(command).receipt).isEqualTo(first)
    }

    @Test
    fun `release and handover serialize on the same ownership boundary`() {
        val command = fixture()
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val handover =
                executor.submit<Throwable?> {
                    barrier.await(10, TimeUnit.SECONDS)
                    runCatching { handovers.handOver(command) }.exceptionOrNull()
                }
            val release =
                executor.submit<Throwable?> {
                    barrier.await(10, TimeUnit.SECONDS)
                    runCatching {
                        releases.release(
                            HumanFollowUpReleaseCommand(
                                command.tenantId,
                                command.workItemId,
                                command.claimId,
                                command.actorId,
                                1,
                            ),
                        )
                    }.exceptionOrNull()
                }
            val failures = listOf(handover.get(20, TimeUnit.SECONDS), release.get(20, TimeUnit.SECONDS))
            assertThat(failures.count { it == null }).isEqualTo(1)
        }
        assertThat(eventCount(command)).isEqualTo(2)
    }

    @Test
    fun `authority decision is sampled only after acquiring the work item lock`() {
        val command = fixture()
        val waiting = CountDownLatch(1)
        val reads = AtomicInteger()
        val observedClaims =
            object : HumanFollowUpClaimRepository by claimRepository {
                override fun lockOpenWorkItem(
                    tenantId: TenantId,
                    workItemId: HumanFollowUpWorkItemId,
                ): Boolean {
                    waiting.countDown()
                    return claimRepository.lockOpenWorkItem(tenantId, workItemId)
                }
            }
        val expiredClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = Clock.fixed(NOW.plusSeconds(3600), zone)

                override fun instant(): Instant {
                    reads.incrementAndGet()
                    return NOW.plusSeconds(3600)
                }
            }
        val service =
            HumanFollowUpHandoverService(
                observedClaims,
                handoverRepository,
                authorities,
                identities,
                transactionRunner,
                expiredClock,
                true,
            )
        Executors.newSingleThreadExecutor().use { executor ->
            val pending =
                requireNotNull(
                    transactions.execute {
                        claimRepository.lockOpenWorkItem(
                            TenantId(command.tenantId),
                            HumanFollowUpWorkItemId(command.workItemId),
                        )
                        val future =
                            executor.submit<Throwable?> {
                                runCatching { service.handOver(command) }.exceptionOrNull()
                            }
                        assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue()
                        assertThat(reads.get()).isZero()
                        future
                    },
                )
            assertThat(pending.get(20, TimeUnit.SECONDS))
                .isInstanceOf(HumanFollowUpHandoverNotFoundException::class.java)
        }
        assertThat(reads.get()).isEqualTo(1)
        assertThat(eventCount(command)).isEqualTo(1)
    }

    private fun serviceAt(
        at: Instant,
        enabled: Boolean = true,
    ): HumanFollowUpHandoverService =
        HumanFollowUpHandoverService(
            claimRepository,
            handoverRepository,
            authorities,
            identities,
            transactionRunner,
            Clock.fixed(at, ZoneOffset.UTC),
            enabled,
        )

    @Test
    fun `concurrent identical intent returns one durable winner without a second claim`() {
        val command = fixture()
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                List(2) {
                    executor.submit<HumanFollowUpHandoverRecording> {
                        barrier.await(10, TimeUnit.SECONDS)
                        handovers.handOver(command)
                    }
                }.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results[0].receipt).isEqualTo(results[1].receipt)
        }
        assertThat(eventCount(command)).isEqualTo(2)
        assertThat(notificationCount(command)).isEqualTo(1)
    }

    @Test
    fun `delivery and source disappear together on transaction rollback`() {
        val command = fixture()
        transactions.executeWithoutResult { transaction ->
            handovers.handOver(command)
            assertThat(notificationCount(command)).isEqualTo(1)
            transaction.setRollbackOnly()
        }
        assertThat(notificationCount(command)).isZero()
        assertThat(handoverRepository.find(command)).isNull()
        assertThat(eventCount(command)).isEqualTo(1)
        assertThat(claims.listOwned(command.tenantId, command.actorId, 50, null, null).items)
            .hasSize(1)
    }

    @Test
    fun `deliveries reject mutation including truncate and survive later ownership changes`() {
        val command = fixture()
        val receipt = handovers.handOver(command).receipt
        releases.release(
            HumanFollowUpReleaseCommand(
                command.tenantId,
                command.workItemId,
                receipt.successorClaimId.value,
                command.recipientActorId,
                receipt.ownershipRevision.value,
            ),
        )
        assertThat(notificationCount(command)).isEqualTo(1)
        for (sql in listOf(
            "UPDATE human_follow_up_handover_notifications SET delivered_at = delivered_at WHERE tenant_id = :tenant",
            "DELETE FROM human_follow_up_handover_notifications WHERE tenant_id = :tenant",
        )) {
            assertThatThrownBy { jdbc.sql(sql).param("tenant", command.tenantId).update() }
                .isInstanceOf(DataAccessException::class.java)
        }
        assertThatThrownBy { jdbc.sql("TRUNCATE human_follow_up_handover_notifications").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThat(notificationCount(command)).isEqualTo(1)
    }

    @Test
    fun `upgrade preserves old receipts without alerts and pins recipient and tenant to the source`() {
        val command = fixture()
        handovers.handOver(command)
        val schema = "notification_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway() =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
        flyway().target("20261026102606").load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                // Copy a genuinely authorized old-format receipt, isolating only its earlier source graph.
                // Notification constraints/triggers do not exist yet; they are never bypassed by this test.
                statement.execute("SET LOCAL session_replication_role = replica")
                val columns =
                    "tenant_id, work_item_id, command_id, sender_actor_id, sender_authority_evidence_id, " +
                        "original_claim_id, expected_ownership_revision, recipient_actor_id, successor_claim_id, " +
                        "ownership_revision, handed_over_at, recorded_at"
                statement.executeUpdate(
                    "INSERT INTO $schema.human_follow_up_handovers ($columns) " +
                        "SELECT $columns FROM public.human_follow_up_handovers " +
                        "WHERE tenant_id = '${command.tenantId}' AND work_item_id = '${command.workItemId}'",
                )
                statement.execute("SET LOCAL session_replication_role = origin")
            }
            connection.commit()
        }
        flyway().load().migrate()
        flyway().load().validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                assertUpgradedNotificationSource(statement, command)
            }
        }
    }

    private fun assertUpgradedNotificationSource(
        statement: Statement,
        command: HumanFollowUpHandoverCommand,
    ) {
        val counts = listOf("human_follow_up_handovers" to 1, "human_follow_up_handover_notifications" to 0)
        for ((table, count) in counts) {
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isEqualTo(count)
            }
        }
        for ((tenant, recipient) in listOf(
            command.tenantId to command.actorId,
            UUID.randomUUID() to command.recipientActorId,
        )) {
            assertNotificationSourceConstraint(statement, command, tenant, recipient)
        }
    }

    private fun assertNotificationSourceConstraint(
        statement: Statement,
        command: HumanFollowUpHandoverCommand,
        tenant: UUID,
        recipient: UUID,
    ) {
        val failure =
            runCatching {
                statement.executeUpdate(
                    """
                    INSERT INTO human_follow_up_handover_notifications (
                        tenant_id, work_item_id, command_id, recipient_actor_id
                    ) VALUES ('$tenant', '${command.workItemId}', '${command.commandId}', '$recipient')
                    """.trimIndent(),
                )
            }.exceptionOrNull()
        assertThat((failure as? SQLException)?.sqlState).isEqualTo("23503")
    }

    private fun notificationCount(command: HumanFollowUpHandoverCommand): Long =
        jdbc
            .sql(
                """
                SELECT COUNT(*) FROM human_follow_up_handover_notifications
                WHERE tenant_id = :tenant AND work_item_id = :work
                    AND command_id = :command AND recipient_actor_id = :recipient
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .param("command", command.commandId)
            .param("recipient", command.recipientActorId)
            .query(Long::class.java)
            .single()

    @Test
    fun `disabled rollout leaves ownership unchanged`() {
        val command = fixture()
        assertThatThrownBy { serviceAt(NOW, enabled = false).handOver(command) }
            .isInstanceOf(HumanFollowUpHandoverUnavailableException::class.java)
        assertThat(eventCount(command)).isEqualTo(1)
    }

    @Test
    fun `receipts are immutable and cannot reference another tenant ownership`() {
        val command = fixture()
        handovers.handOver(command)
        listOf(
            "UPDATE human_follow_up_handovers SET command_id = command_id WHERE tenant_id = :tenant",
            "DELETE FROM human_follow_up_handovers WHERE tenant_id = :tenant",
        ).forEach { sql ->
            assertThatThrownBy { jdbc.sql(sql).param("tenant", command.tenantId).update() }
                .isInstanceOf(DataAccessException::class.java)
        }
        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_handovers (
                        tenant_id, work_item_id, command_id, sender_actor_id, sender_authority_evidence_id,
                        original_claim_id, expected_ownership_revision, recipient_actor_id, successor_claim_id,
                        ownership_revision, handed_over_at
                    )
                    SELECT :foreignTenant, work_item_id, command_id, sender_actor_id, sender_authority_evidence_id,
                        original_claim_id, expected_ownership_revision, recipient_actor_id, successor_claim_id,
                        ownership_revision, handed_over_at
                    FROM human_follow_up_handovers WHERE tenant_id = :tenant
                    """.trimIndent(),
                ).param("foreignTenant", UUID.randomUUID())
                .param("tenant", command.tenantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun fixture(): HumanFollowUpHandoverCommand {
        val graph = requireNotNull(transactions.execute { RunSupervisionDatabaseFixture.seed(jdbc, NOW) })
        val work = UUID.randomUUID()
        transactions.executeWithoutResult {
            // Only the earlier escalation source graph is isolated, never ownership or handover integrity.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
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
        val claim = claims.claim(graph.tenant.value, work, graph.outgoing.value).storedClaim.claim
        return HumanFollowUpHandoverCommand(
            graph.tenant.value,
            work,
            graph.outgoing.value,
            UUID.randomUUID(),
            claim.id.value,
            1,
            graph.incoming.value,
        )
    }

    private fun post(command: HumanFollowUpHandoverCommand) =
        mvc.perform(
            post(path(command))
                .with(RunEscalationTestFixture.session(command.actorId))
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(command)),
        )

    private fun eventCount(command: HumanFollowUpHandoverCommand): Long =
        jdbc
            .sql(
                """
                SELECT COUNT(*) FROM human_follow_up_ownership_events
                WHERE tenant_id = :tenant AND work_item_id = :work
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .query(Long::class.java)
            .single()

    companion object {
        val NOW: Instant = Instant.parse("2026-10-23T10:00:00Z")

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
private class HandoverClockConfiguration {
    @Bean @Primary
    fun handoverClock(): Clock = Clock.fixed(HumanFollowUpHandoverIntegrationTest.NOW, ZoneOffset.UTC)
}

private fun path(command: HumanFollowUpHandoverCommand): String =
    "/bff/v1/tenants/${command.tenantId}/human-follow-ups/${command.workItemId}/handovers"

private fun body(command: HumanFollowUpHandoverCommand): String =
    """{"commandId":"${command.commandId}","claimId":"${command.claimId}",
        "expectedOwnershipRevision":${command.expectedOwnershipRevision},
        "recipientActorId":"${command.recipientActorId}"}"""
