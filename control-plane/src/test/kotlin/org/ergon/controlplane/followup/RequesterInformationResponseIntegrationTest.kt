package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.RequesterInformationResponse
import org.ergon.controlplane.followup.application.RequesterInformationResponseCommand
import org.ergon.controlplane.followup.application.RequesterInformationResponseConflictException
import org.ergon.controlplane.followup.application.RequesterInformationResponseNotFoundException
import org.ergon.controlplane.followup.application.RequesterInformationResponseRepository
import org.ergon.controlplane.followup.application.RequesterInformationResponseService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.RequesterInformationAnswer
import org.ergon.followup.domain.RequesterInformationQuestion
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.followup.domain.RequesterInformationResponseId
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.security.browser-session.audience=requester",
        "ergon.follow-up.release-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(InformationResponseClockConfiguration::class, BrowserSessionTestClientConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Real HTTP/session and storage/transaction boundaries share one immutable source fixture.
@Suppress("LongParameterList")
class RequesterInformationResponseIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val questions: RequesterInformationRequestRepository,
    @Autowired private val responses: RequesterInformationResponseRepository,
    @Autowired private val service: RequesterInformationResponseService,
    @Autowired private val mvc: MockMvc,
    @Autowired private val registrations: ClientRegistrationRepository,
    @Autowired private val releases: HumanFollowUpReleaseService,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `requester CSRF and exact replay protect a narrow immutable receipt without resolver authority`() {
        val command = command()
        mvc.perform(post(path(command))).andExpect(status().isUnauthorized)
        mvc.perform(post(path(command)).with(login(command.actorId))).andExpect(status().isForbidden)
        val workbench = BrowserSessionTestClientConfiguration().browserClientRegistrationRepository("workbench")
        mvc
            .perform(
                post(path(command)).with(csrf()).with(
                    oidcLogin()
                        .clientRegistration(requireNotNull(workbench.findByRegistrationId("ergon-workbench"))),
                ),
            ).andExpect(status().isForbidden)
        val first = send(command, 201)
        assertThat(send(command, 200)).isEqualTo(first)
        assertThat(first).doesNotContain("ActorId", "workItem", "claim", "provider", "authority")
        val receipt = mapper.readTree(first)
        assertThat(receipt["answer"].asString()).isEqualTo(command.answer)
        assertThat(receipt.properties().map { it.key }).containsExactlyInAnyOrder(
            "responseId",
            "requestId",
            "caseId",
            "answer",
            "respondedAt",
            "recordedAt",
        )
        send(command.copy(answer = "changed"), 409)
        send(command.copy(responseId = UUID.randomUUID()), 409)
        send(command.copy(answer = "\u00A0"), 400)
        mvc
            .perform(
                post(path(command))
                    .with(login(command.actorId))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"responseId":"not-a-uuid","answer":"text"}"""),
            ).andExpect(status().isBadRequest)
        assertThat(count(command)).isEqualTo(1)
        assertThat(
            jdbc
                .sql("SELECT stream_version FROM cases WHERE tenant_id = :tenant AND case_id = :case")
                .param("tenant", command.tenantId)
                .param("case", command.caseId)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1)
    }

    @Test
    fun `hidden questions cases and closed replay use the same neutral failure`() {
        val command = command()
        val other = command()
        val foreignActor =
            jdbc
                .sql("SELECT actor_id FROM human_actors WHERE tenant_id = :tenant AND actor_id <> :actor LIMIT 1")
                .param("tenant", command.tenantId)
                .param("actor", command.actorId)
                .query(UUID::class.java)
                .single()
        val denied =
            listOf(
                command.copy(requestId = UUID.randomUUID()),
                command.copy(caseId = UUID.randomUUID()),
                command.copy(actorId = foreignActor),
                command.copy(caseId = other.caseId, requestId = other.requestId),
            ).map { mapper.readTree(send(it, 404))["type"].asString() }
        assertThat(denied.distinct()).containsExactly("urn:ergon:problem:requester-response-not-found")
        assertThatThrownBy { service.record(command.copy(tenantId = other.tenantId)) }
            .isInstanceOf(RequesterInformationResponseNotFoundException::class.java)
        send(command, 201)
        transactions.executeWithoutResult {
            jdbc
                .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :tenant AND case_id = :case")
                .param("tenant", command.tenantId)
                .param("case", command.caseId)
                .update()
        }
        send(command, 404)
        assertThat(count(command)).isEqualTo(1)
    }

    @Test
    fun `concurrent exact commands acknowledge once while changed intent conflicts without extra rows`() {
        val command = command()
        val results = RunEscalationTestFixture.together({ service.record(command) }, { service.record(command) })
        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results.map { it.stored }.distinct()).hasSize(1)
        val fresh = command(TenantId(command.tenantId)).copy(responseId = command.responseId)
        assertThatThrownBy { service.record(fresh) }
            .isInstanceOf(RequesterInformationResponseConflictException::class.java)
        assertThat(count(fresh)).isZero()
        assertThat(count(command)).isEqualTo(1)
    }

    @Test
    fun `resolver release does not remove the explicit requesters right to answer an open case`() {
        val (tenant, question) = question(requesterWithoutAuthority = true)
        releases.release(
            HumanFollowUpReleaseCommand(
                tenant.value,
                question.workItemId.value,
                question.claimId.value,
                question.authorActorId.value,
                question.ownershipRevision.value,
            ),
        )
        val actor =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant")
                .param("tenant", tenant.value)
                .query(UUID::class.java)
                .single()
        val result =
            service.record(
                RequesterInformationResponseCommand(
                    tenant.value,
                    question.caseId.value,
                    question.requestId.value,
                    actor,
                    UUID.randomUUID(),
                    "still my answer",
                ),
            )
        assertThat(result.created).isTrue()
    }

    @Test
    fun `new answers and exact recovery waiting on closure are withdrawn after the case lock`() {
        for (replay in listOf(false, true)) {
            val command = command()
            if (replay) service.record(command)
            val pid = AtomicInteger()
            Executors.newSingleThreadExecutor().use { executor ->
                val pending =
                    requireNotNull(
                        transactions.execute {
                            jdbc
                                .sql(
                                    """
                                    UPDATE cases SET status = 'VERIFIED_RESOLVED'
                                    WHERE tenant_id = :tenant AND case_id = :case
                                    """.trimIndent(),
                                ).param("tenant", command.tenantId)
                                .param("case", command.caseId)
                                .update()
                            val future =
                                executor.submit<Throwable?> {
                                    runCatching {
                                        transactions.execute {
                                            pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                                            service.record(command)
                                        }
                                    }.exceptionOrNull()
                                }
                            // Observe the actual PostgreSQL wait, not timing or a pre-request latch.
                            await().atMost(20, TimeUnit.SECONDS).until {
                                pid.get() != 0 &&
                                    jdbc
                                        .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                                        .param("pid", pid.get())
                                        .query(Boolean::class.java)
                                        .single()
                            }
                            future
                        },
                    )
                assertThat(pending.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(RequesterInformationResponseNotFoundException::class.java)
            }
            assertThat(count(command)).isEqualTo(if (replay) 1 else 0)
        }
    }

    private fun command(tenant: TenantId = TenantId(UUID.randomUUID())): RequesterInformationResponseCommand {
        val (_, request) = question(tenant, requesterWithoutAuthority = true)
        val actor =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant AND case_id = :case")
                .param("tenant", tenant.value)
                .param("case", request.caseId.value)
                .query(UUID::class.java)
                .single()
        return RequesterInformationResponseCommand(
            tenant.value,
            request.caseId.value,
            request.requestId.value,
            actor,
            UUID.randomUUID(),
            " exact 😀 answer \n",
        )
    }

    private fun login(actor: UUID) =
        oidcLogin()
            .clientRegistration(requireNotNull(registrations.findByRegistrationId("ergon-requester")))
            .idToken { it.issuer("https://identity.example.test").subject("resolver-$actor") }

    private fun path(command: RequesterInformationResponseCommand) =
        "/bff/v1/tenants/${command.tenantId}/requester-cases/${command.caseId}" +
            "/information-requests/${command.requestId}/responses"

    private fun send(
        command: RequesterInformationResponseCommand,
        expected: Int,
    ): String {
        val body = mapper.writeValueAsString(mapOf("responseId" to command.responseId, "answer" to command.answer))
        return mvc
            .perform(
                post(path(command))
                    .with(login(command.actorId))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().`is`(expected))
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andReturn()
            .response.contentAsString
    }

    private fun count(command: RequesterInformationResponseCommand) =
        jdbc
            .sql(
                """
                SELECT COUNT(*) FROM requester_information_responses
                WHERE tenant_id = :tenant AND request_id = :request
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("request", command.requestId)
            .query(Long::class.java)
            .single()

    @Test
    fun `replay preserves database winner attribution precision and exact text`() {
        val (tenant, question) = question()
        val intent = response(question).copy(respondedAt = NOW.plusNanos(123))
        val first = record(tenant, intent)
        assertThat(first.created).isTrue()
        assertThat(first.stored.response.answer).isEqualTo(intent.answer)
        assertThat(first.stored.response.respondedAt.nano % 1000).isZero()
        assertThat(first.stored.caseId).isEqualTo(question.caseId)
        val requester =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant")
                .param("tenant", tenant.value)
                .query(UUID::class.java)
                .single()
        assertThat(first.stored.requesterActorId.value).isEqualTo(requester)
        val replay = record(tenant, intent.copy(answer = RequesterInformationAnswer("losing text")))
        assertThat(replay.created).isFalse()
        assertThat(replay.stored).isEqualTo(first.stored)
        assertThat(record(tenant, response(question)).stored).isEqualTo(first.stored)
    }

    @Test
    fun `competing response and question identities never merge the two winners`() {
        val (tenant, firstQuestion) = question()
        val (_, secondQuestion) = question(tenant)
        val first = record(tenant, response(firstQuestion)).stored
        val second = record(tenant, response(secondQuestion)).stored
        val collision = second.response.copy(responseId = first.response.responseId)
        assertThat(record(tenant, collision).stored).isEqualTo(first)
        assertThat(record(tenant, second.response).stored).isEqualTo(second)
        val (foreignTenant, foreignQuestion) = question()
        val foreign = response(foreignQuestion).copy(responseId = first.response.responseId)
        assertThat(record(foreignTenant, foreign).created).isTrue()
        assertThatThrownBy { record(foreignTenant, response(firstQuestion)) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            record(
                tenant,
                first.response.copy(
                    requestId = RequesterInformationRequestId(UUID.randomUUID()),
                    responseId = RequesterInformationResponseId(UUID.randomUUID()),
                ),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `concurrent exact and different intents return one whole committed answer`() {
        for (exact in listOf(true, false)) {
            val (tenant, question) = question()
            val intent = response(question)
            val other =
                if (exact) {
                    intent
                } else {
                    response(question).copy(
                        answer = RequesterInformationAnswer("competing answer"),
                        respondedAt = NOW.plusSeconds(1),
                    )
                }
            val outcomes = RunEscalationTestFixture.together({ record(tenant, intent) }, { record(tenant, other) })
            assertThat(outcomes.count { it.created }).isEqualTo(1)
            assertThat(outcomes.map { it.stored }.distinct()).hasSize(1)
            assertThat(outcomes.first().stored.response).isIn(intent, other)
        }
    }

    @Test
    fun `SQL enforces text parity one answer and immutable audit`() {
        val (tenant, question) = question()
        val intent = response(question)
        for (blank in listOf("", "\u00A0\u2007\u202F", "\u001C\u3000", "😀".repeat(4001))) {
            assertThatThrownBy { insertRaw(tenant, intent, blank) }.isInstanceOf(DataAccessException::class.java)
        }
        val stored = record(tenant, intent.copy(answer = RequesterInformationAnswer("😀".repeat(4000)))).stored
        assertThat(stored.response.answer.value).isEqualTo("😀".repeat(4000))
        assertThatThrownBy { insertRaw(tenant, response(question), "second answer") }
            .isInstanceOf(DataAccessException::class.java)
        for (sql in listOf(
            "UPDATE requester_information_responses SET answer = answer",
            "DELETE FROM requester_information_responses",
            "TRUNCATE requester_information_responses",
        )) {
            assertThatThrownBy { jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(record(tenant, intent).stored).isEqualTo(stored)
    }

    @Test
    fun `upgrade retains existing questions and supports response writer after full empty migration chain`() {
        val schema = "response_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway() =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
        flyway().target("20261025185000").load().migrate()
        lateinit var retained: Pair<TenantId, RequesterInformationRequest>
        transactions.executeWithoutResult {
            jdbc.sql("SET LOCAL search_path TO $schema").update()
            retained = question()
        }
        flyway().load().migrate()
        flyway().load().validate()
        transactions.executeWithoutResult {
            jdbc.sql("SET LOCAL search_path TO $schema").update()
            val (tenant, question) = retained
            val read = questions.record(tenant, question)
            assertThat(read.created).isFalse()
            assertThat(read.stored.request).isEqualTo(question)
            assertThat(record(tenant, response(question)).created).isTrue()
        }
    }

    private fun insertRaw(
        tenant: TenantId,
        response: RequesterInformationResponse,
        answer: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO requester_information_responses (tenant_id, response_id, request_id, answer, responded_at)
                VALUES (:tenant, :id, :request, :answer, :at)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", response.responseId.value)
            .param("request", response.requestId.value)
            .param("answer", answer)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun response(question: RequesterInformationRequest) =
        RequesterInformationResponse(
            RequesterInformationResponseId(UUID.randomUUID()),
            question.requestId,
            RequesterInformationAnswer(" <script>untrusted answer</script> \n"),
            NOW,
        )

    private fun record(
        tenant: TenantId,
        response: RequesterInformationResponse,
    ) = requireNotNull(transactions.execute { responses.record(tenant, response) })

    private fun question(
        tenant: TenantId = TenantId(UUID.randomUUID()),
        requesterWithoutAuthority: Boolean = false,
    ): Pair<TenantId, RequesterInformationRequest> =
        requireNotNull(
            transactions.execute {
                val graph = RunSupervisionDatabaseFixture.seed(jdbc, NOW, tenant = tenant)
                val requester = if (requesterWithoutAuthority) registerRequester(tenant) else graph.incoming.value
                val work = UUID.randomUUID()
                // Only earlier escalation construction is synthetic; question/response constraints stay enabled.
                jdbc.sql("SET LOCAL session_replication_role = replica").update()
                jdbc
                    .sql(
                        """
                        INSERT INTO human_follow_up_work_items
                            (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                        VALUES (:tenant, :work, :run, :event, 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :at)
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("work", work)
                    .param("run", graph.run.value)
                    .param("event", UUID.randomUUID())
                    .param("at", NOW.atOffset(ZoneOffset.UTC))
                    .update()
                jdbc.sql("SET LOCAL session_replication_role = origin").update()
                val caseId =
                    jdbc
                        .sql("SELECT case_id FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run")
                        .param("tenant", tenant.value)
                        .param("run", graph.run.value)
                        .query(UUID::class.java)
                        .single()
                jdbc
                    .sql(
                        """
                        INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
                        VALUES (:tenant, :case, :actor, 'synthetic reviewed requester binding')
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("case", caseId)
                    .param("actor", requester)
                    .update()
                val claim = claims.claim(tenant.value, work, graph.outgoing.value).storedClaim.claim
                val request =
                    RequesterInformationRequest(
                        RequesterInformationRequestId(UUID.randomUUID()),
                        HumanFollowUpWorkItemId(work),
                        CaseId(caseId),
                        claim.id,
                        HumanFollowUpOwnershipRevision(1),
                        claim.resolverActorId,
                        RequesterInformationQuestion("Which workspace?"),
                        NOW,
                    )
                tenant to questions.record(tenant, request).stored.request
            },
        )

    /** Registers identity only; no resolver or approval attestation exists for this actor. */
    private fun registerRequester(tenant: TenantId): UUID {
        val actor = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'workforce-sso', :subject, :at)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("actor", actor)
            .param("subject", "resolver-$actor")
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .update()
        return actor
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-26T10:00:00Z")

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

@TestConfiguration(proxyBeanMethods = false)
class InformationResponseClockConfiguration {
    @Bean @Primary
    fun responseClock(): Clock = Clock.fixed(RequesterInformationResponseIntegrationTest.NOW, ZoneOffset.UTC)
}
