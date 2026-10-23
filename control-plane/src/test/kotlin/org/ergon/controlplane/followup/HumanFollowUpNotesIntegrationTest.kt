package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommand
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpNoteCommand
import org.ergon.controlplane.followup.application.HumanFollowUpNoteConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpNoteRepository
import org.ergon.controlplane.followup.application.HumanFollowUpNoteService
import org.ergon.controlplane.followup.application.HumanFollowUpNotesNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.follow-up.release-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, NoteClockConfiguration::class)
@Suppress("LongParameterList") // The integration test exercises real independent application/persistence boundaries.
class HumanFollowUpNotesIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val notes: HumanFollowUpNoteService,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val claimRepository: HumanFollowUpClaimRepository,
    @Autowired private val releases: HumanFollowUpReleaseService,
    @Autowired private val noteRepository: HumanFollowUpNoteRepository,
    @Autowired private val transactionRunner: TransactionRunner,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

    @Test
    fun `browser requires session CSRF and tenant binding`() {
        val command = fixture()
        val path = path(command)
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path)
                    .with(RunEscalationTestFixture.session(command.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        mvc
            .perform(get(path).with(RunEscalationTestFixture.session(UUID.randomUUID())))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `browser returns exact attributable receipt replay and current history`() {
        val command = fixture()
        val path = path(command)
        val created =
            mvc
                .perform(
                    post(path)
                        .with(RunEscalationTestFixture.session(command.actorId))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(command)),
                ).andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.authorActorId").value(command.actorId.toString()))
                .andExpect(jsonPath("$.text").value(command.text))
                .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andReturn()
                .response.contentAsString
        val replay =
            mvc
                .perform(
                    post(path)
                        .with(RunEscalationTestFixture.session(command.actorId))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(command)),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(replay).isEqualTo(created)
        mvc
            .perform(get(path).with(RunEscalationTestFixture.session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].noteId").value(command.noteId.toString()))
            .andExpect(jsonPath("$.truncated").value(false))
    }

    @Test
    fun `browser rejects invalid text revision and changed note intent`() {
        val command = fixture()
        val path = path(command)
        notes.append(command)
        listOf(body(command.copy(text = " ")), body(command, expectedRevision = 0)).forEach { invalid ->
            mvc
                .perform(
                    post(path)
                        .with(RunEscalationTestFixture.session(command.actorId))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-note"))
        }
        mvc
            .perform(
                post(path)
                    .with(RunEscalationTestFixture.session(command.actorId))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command.copy(text = "changed"))),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `release retires read and replay but a later owner sees original authorship`() {
        val command = fixture()
        val first = notes.append(command)
        releases.release(
            HumanFollowUpReleaseCommand(
                command.tenantId,
                command.workItemId,
                command.claimId,
                command.actorId,
                1,
            ),
        )
        assertThatThrownBy { notes.append(command) }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        assertThatThrownBy {
            notes.list(command.tenantId, command.workItemId, command.actorId)
        }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        // Pick the other fixture actor with live authority rather than its deliberately expired actor.
        val successor =
            jdbc
                .sql(
                    """
                    SELECT actor_id FROM approval_authority_evidence
                    WHERE tenant_id = :tenant AND actor_id <> :actor AND expires_at > :now
                    LIMIT 1
                    """.trimIndent(),
                ).param(
                    "tenant",
                    command.tenantId,
                ).param("actor", command.actorId)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .query(UUID::class.java)
                .single()
        mvc
            .perform(get(path(command)).with(RunEscalationTestFixture.session(successor)))
            .andExpect(status().isNotFound)
        assertThatThrownBy {
            notes.append(command.copy(actorId = successor))
        }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        claims.claimWithCommand(
            HumanFollowUpClaimCommand(command.tenantId, command.workItemId, successor, UUID.randomUUID(), 2),
        )
        assertThat(notes.list(command.tenantId, command.workItemId, successor).items).containsExactly(first.stored)
        assertThatThrownBy {
            notes.append(command.copy(actorId = successor))
        }.isInstanceOf(HumanFollowUpNoteConflictException::class.java)
    }

    @Test
    fun `concurrent exact appends persist once and stale or changed intents conflict`() {
        val command = fixture()
        val results = RunEscalationTestFixture.together({ notes.append(command) }, { notes.append(command) })
        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results.map { it.stored }.distinct()).hasSize(1)
        assertThatThrownBy {
            notes.append(command.copy(text = "changed"))
        }.isInstanceOf(HumanFollowUpNoteConflictException::class.java)
        assertThatThrownBy {
            notes.append(command.copy(expectedOwnershipRevision = 3))
        }.isInstanceOf(HumanFollowUpNoteConflictException::class.java)
        assertThatThrownBy {
            notes.list(UUID.randomUUID(), command.workItemId, command.actorId)
        }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
    }

    @Test
    fun `lost authority hides note reads and exact receipt replay`() {
        val command = fixture()
        notes.append(command)
        val expired =
            HumanFollowUpNoteService(
                claimRepository,
                noteRepository,
                transactionRunner,
                Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC),
            )
        assertThatThrownBy {
            expired.list(command.tenantId, command.workItemId, command.actorId)
        }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        assertThatThrownBy { expired.append(command) }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        mvc
            .perform(get(path(command)).with(RunEscalationTestFixture.session(UUID.randomUUID())))
            .andExpect(status().isForbidden)
        mvc
            .perform(
                get(path(command.copy(workItemId = UUID.randomUUID())))
                    .with(RunEscalationTestFixture.session(command.actorId)),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-notes-not-found"))
    }

    @Test
    fun `append races release without attaching a note after ownership is gone`() {
        val command = fixture()
        val outcomes =
            RunEscalationTestFixture.together(
                {
                    runCatching {
                        notes.append(command)
                        "note"
                    }
                },
                {
                    runCatching {
                        releases.release(
                            HumanFollowUpReleaseCommand(
                                tenantId = command.tenantId,
                                workItemId = command.workItemId,
                                claimId = command.claimId,
                                actorId = command.actorId,
                                expectedOwnershipRevision = 1,
                            ),
                        )
                        "release"
                    }
                },
            )
        assertThat(outcomes[1].getOrThrow()).isEqualTo("release")
        outcomes[0].exceptionOrNull()?.let {
            assertThat(it).isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
        }
        assertThatThrownBy {
            notes.list(command.tenantId, command.workItemId, command.actorId)
        }.isInstanceOf(HumanFollowUpNotesNotFoundException::class.java)
    }

    @Test
    fun `notes are immutable tenant safe and bounded newest first with explicit truncation`() {
        val command = fixture()
        val original = notes.append(command)
        jdbc
            .sql(
                """
                INSERT INTO human_follow_up_notes (tenant_id, work_item_id, note_id, ownership_revision, body, authored_at, recorded_at)
                SELECT :tenant, :work, gen_random_uuid(), 1, 'history', :at, :at
                FROM generate_series(1, 100) sequence
                """.trimIndent(),
            ).param(
                "tenant",
                command.tenantId,
            ).param("work", command.workItemId)
            .param("at", original.stored.recordedAt.atOffset(ZoneOffset.UTC))
            .update()
        val page = notes.list(command.tenantId, command.workItemId, command.actorId)
        assertThat(page.items).hasSize(100)
        assertThat(page.truncated).isTrue()
        assertThat(page.items.map { it.recordedAt }).isSortedAccordingTo(reverseOrder())
        assertThat(
            page.items.map {
                it.note.id.value
                    .toString()
            },
        ).isSortedAccordingTo(reverseOrder())
        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    UPDATE human_follow_up_notes SET body = 'changed'
                    WHERE tenant_id = :tenant
                    """.trimIndent(),
                ).param("tenant", command.tenantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc
                .sql("DELETE FROM human_follow_up_notes WHERE tenant_id = :tenant")
                .param("tenant", command.tenantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_notes (tenant_id, work_item_id, note_id, ownership_revision, body, authored_at)
                    VALUES (:tenant, :work, :id, 1, 'forged', :at)
                    """.trimIndent(),
                ).param(
                    "tenant",
                    UUID.randomUUID(),
                ).param("work", command.workItemId)
                .param("id", UUID.randomUUID())
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun fixture(): HumanFollowUpNoteCommand {
        val graph = requireNotNull(transactions.execute { RunSupervisionDatabaseFixture.seed(jdbc, NOW) })
        val work = UUID.randomUUID()
        transactions.executeWithoutResult {
            // Isolate the existing escalation source graph; notes and ownership
            // themselves always run with normal FKs and mutation guards enabled.
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
        return HumanFollowUpNoteCommand(
            graph.tenant.value,
            work,
            graph.outgoing.value,
            UUID.randomUUID(),
            claim.id.value,
            1,
            "<script>inert</script>",
        )
    }

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
private class NoteClockConfiguration {
    @Bean @Primary
    fun noteClock(): Clock = Clock.fixed(HumanFollowUpNotesIntegrationTest.NOW, ZoneOffset.UTC)
}

private fun path(command: HumanFollowUpNoteCommand): String =
    "/bff/v1/tenants/${command.tenantId}/human-follow-ups/${command.workItemId}/notes"

private fun body(
    command: HumanFollowUpNoteCommand,
    expectedRevision: Long = command.expectedOwnershipRevision,
): String =
    """{"noteId":"${command.noteId}",
        "claimId":"${command.claimId}",
        "expectedOwnershipRevision":$expectedRevision,
        "text":"${command.text}"}"""
