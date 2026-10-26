package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.OwnedInformationRequestsService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.AttestApprovalAuthorityCommand
import org.ergon.controlplane.identity.application.HumanAuthorityService
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.annotation.DirtiesContext
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.security.browser-session.audience=requester",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Import(BrowserSessionTestClientConfiguration::class, RequesterCaseProgressIntegrationTest.FixedClock::class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Both browser projections share real requester identity, transactions and ownership fixtures.
@Suppress("LongParameterList")
class RequesterCaseProgressIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val cases: CaseCommandService,
    @Autowired private val actors: HumanAuthorityService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val registrations: ClientRegistrationRepository,
    @Autowired private val clock: Clock,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val history: OwnedInformationRequestsService,
    @Autowired manager: PlatformTransactionManager,
) {
    private val mapper = JsonMapper.builder().build()
    private val transactions = TransactionTemplate(manager)

    @Test
    fun `question history is requester safe bounded deterministic and survives case closure`() {
        val (graph, work, case) = questionWork()
        val tenant = graph.tenant
        val path = "/bff/v1/tenants/${tenant.value}/requester-cases/$case/information-requests"
        mvc
            .perform(get(path).with(login("question-reader")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.truncated").value(false))
        recordBoundedQuestionHistory(tenant, work, case)
        val response =
            mvc
                .perform(get(path).with(login("question-reader")))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.truncated").value(true))
                .andReturn()
                .response.contentAsString
        val page = mapper.readTree(response)
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "truncated")
        assertThat(page["items"].size()).isEqualTo(100)
        assertThat(page["items"][0].propertyNames())
            .containsExactlyInAnyOrder("requestId", "question", "requestedAt", "recordedAt", "response")
        assertThat(page["items"][0]["requestId"].asText()).isEqualTo(UUID(0, 101).toString())
        assertThat(page["items"][99]["requestId"].asText()).isEqualTo(UUID(0, 2).toString())
        val owned = history.list(tenant.value, work, graph.outgoing.value)
        assertThat(owned.truncated).isTrue()
        val requesterIds =
            (0 until page["items"].size()).map { index ->
                UUID.fromString(page["items"][index]["requestId"].asText())
            }
        assertThat(owned.items.map { it.stored.request.requestId.value }).containsExactlyElementsOf(requesterIds)
        val answer = page["items"][0]["response"]
        assertRecordedAnswer(answer)
        assertThat(page["items"][1]["response"].isNull).isTrue()
        assertThat(
            owned.items
                .first()
                .response
                ?.answer,
        ).isEqualTo(answer["answer"].asString())
        assertThat(owned.items.count { it.response != null }).isEqualTo(1)
        jdbc
            .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :tenant")
            .param("tenant", tenant.value)
            .update()
        mvc
            .perform(get(path).with(login("question-reader")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(100))
            .andExpect(jsonPath("$.items[0].response.answer").value(" <script>answer</script> 😀 "))
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(get(path).with(oidcLogin())).andExpect(status().isForbidden)
        mvc.perform(post(path).with(login("question-reader"))).andExpect(status().isForbidden)
    }

    @Test
    fun `answers with identical question identities remain tenant scoped and unavailable to other actors`() {
        val requestId = UUID.randomUUID()
        val targets = listOf(questionWork(), questionWork())
        for ((index, target) in targets.withIndex()) {
            val (graph, work, case) = target
            recordAnsweredQuestion(graph.tenant, work, case, requestId, "Private answer $index")
        }
        for ((index, target) in targets.withIndex()) {
            val (graph, work, case) = target
            val path = "/bff/v1/tenants/${graph.tenant.value}/requester-cases/$case/information-requests"
            val result =
                mvc
                    .perform(get(path).with(login("question-reader")))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].response.answer").value("Private answer $index"))
                    .andReturn()
                    .response.contentAsString
            assertThat(result).doesNotContain("Private answer ${1 - index}")
            assertThat(
                history
                    .list(graph.tenant.value, work, graph.outgoing.value)
                    .items
                    .single()
                    .response
                    ?.answer,
            ).isEqualTo("Private answer $index")
            actor(graph.tenant.value, "stranger")
            mvc
                .perform(get(path).with(login("stranger")))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:requester-case-not-found"))
            val foreignCase = targets[1 - index].third
            mvc
                .perform(
                    get("/bff/v1/tenants/${graph.tenant.value}/requester-cases/$foreignCase/information-requests")
                        .with(login("question-reader")),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `owner sees only narrow projection without approval authority and reads never change the case`() {
        val tenant = UUID.randomUUID()
        val actor = actor(tenant, "owner")
        val case = cases.open(OpenCaseCommand(tenant, "Restore access", "private source observation"))
        bind(tenant, case.caseId, actor)
        val before =
            jdbc
                .sql("SELECT stream_version FROM cases WHERE tenant_id = :t AND case_id = :c")
                .param("t", tenant)
                .param("c", case.caseId)
                .query(Long::class.java)
                .single()
        val response =
            mvc
                .perform(get(PATH, tenant, case.caseId).with(login("owner")))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.goal").value("Restore access"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn()
                .response.contentAsString
        assertThat(mapper.readTree(response).propertyNames())
            .containsExactlyInAnyOrder("caseId", "goal", "status", "openedAt", "updatedAt")
        assertThat(response).doesNotContain("private source", actor.toString(), "source_reference")
        assertThat(
            jdbc
                .sql("SELECT stream_version FROM cases WHERE tenant_id = :t AND case_id = :c")
                .param("t", tenant)
                .param("c", case.caseId)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(before)
        // Exercise the reader's other existing projection state, not an alternative proof-acceptance path.
        jdbc
            .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :t AND case_id = :c")
            .param("t", tenant)
            .param("c", case.caseId)
            .update()
        mvc
            .perform(get(PATH, tenant, case.caseId).with(login("owner")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("VERIFIED_RESOLVED"))
    }

    @Test
    fun `foreign unowned absent and cross-tenant cases share identical non-disclosure`() {
        val tenant = UUID.randomUUID()
        actor(tenant, "viewer")
        val owner = actor(tenant, "other")
        val bound = cases.open(OpenCaseCommand(tenant, "Secret goal", "Secret source")).caseId
        val unowned = cases.open(OpenCaseCommand(tenant, "Unowned goal", "Private")).caseId
        bind(tenant, bound, owner)
        val otherTenant = UUID.randomUUID()
        actor(otherTenant, "viewer")
        val targets = listOf(tenant to bound, tenant to unowned, tenant to UUID.randomUUID(), otherTenant to bound)
        val bodies =
            targets.flatMap { (t, c) ->
                listOf("progress", "information-requests").map { suffix ->
                    mvc
                        .perform(
                            get("/bff/v1/tenants/$t/requester-cases/$c/$suffix")
                                .param("actorId", owner.toString())
                                .with(login("viewer")),
                        ).andExpect(status().isNotFound)
                        .andExpect(header().string("Cache-Control", containsString("no-store")))
                        .andExpect(jsonPath("$.type").value("urn:ergon:problem:requester-case-not-found"))
                        .andReturn()
                        .response.contentAsString
                }
            }
        // RFC 9457 instance reflects the caller's path, not case existence.
        val problems = bodies.map { mapper.readTree(it) }
        val fields = listOf("type", "title", "status", "detail")
        assertThat(problems.map { problem -> fields.map { problem[it] } }.distinct()).hasSize(1)
        bodies.forEach { assertThat(it).doesNotContain("Secret goal", "Unowned goal", "Secret source") }
        mvc
            .perform(get(PATH, UUID.randomUUID(), bound).with(login("viewer")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
        mvc.perform(get(PATH, tenant, bound)).andExpect(status().isUnauthorized)
        mvc.perform(get(PATH, tenant, bound).with(oidcLogin())).andExpect(status().isForbidden)
    }

    @Test
    fun `approval attestations never substitute for requester ownership`() {
        val tenant = UUID.randomUUID()
        val actor = actor(tenant, "approver")
        val case = cases.open(OpenCaseCommand(tenant, "Private", "Private")).caseId
        for (authority in listOf(ApprovalAuthority.REQUESTER, ApprovalAuthority.RESOLVER)) {
            actors.attest(
                AttestApprovalAuthorityCommand(
                    tenant,
                    actor,
                    authority,
                    case.takeIf { authority == ApprovalAuthority.REQUESTER },
                    "test",
                    "attestation",
                    clock.instant().plus(Duration.ofHours(1)),
                ),
            )
        }
        mvc.perform(get(PATH, tenant, case).with(login("approver"))).andExpect(status().isNotFound)
    }

    @Test
    fun `binding integrity rejects foreign keys duplicate owners invalid provenance and mutation`() {
        val tenant = UUID.randomUUID()
        val actor = actor(tenant, "owner")
        val case = cases.open(OpenCaseCommand(tenant, "Private", "Private")).caseId
        val foreignActor = actor(UUID.randomUUID(), "foreign")
        val foreignCase = cases.open(OpenCaseCommand(UUID.randomUUID(), "Foreign", "Private")).caseId
        assertThatThrownBy { bind(tenant, case, foreignActor) }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { bind(tenant, foreignCase, actor) }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { bind(tenant, UUID.randomUUID(), actor) }.isInstanceOf(DataAccessException::class.java)
        for (source in listOf(" ", "\t\n", "x".repeat(501))) {
            assertThatThrownBy { bind(tenant, case, actor, source) }.isInstanceOf(DataAccessException::class.java)
        }
        bind(tenant, case, actor)
        assertThatThrownBy { bind(tenant, case, actor) }.isInstanceOf(DataAccessException::class.java)
        val replacement = actor(tenant, "replacement")
        assertThatThrownBy { bind(tenant, case, replacement) }.isInstanceOf(DataAccessException::class.java)
        for (sql in listOf(
            "UPDATE case_requesters SET source_reference = source_reference",
            "DELETE FROM case_requesters",
            "TRUNCATE case_requesters",
        )) {
            assertThatThrownBy { jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `released schema upgrade preserves legacy cases without assigning requesters`() {
        val schema = "requester_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun migrate(target: String) =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .table("ergon_flyway_schema_history")
                .target(target)
                .load()
        migrate("20261024101559").migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO cases (tenant_id, case_id, goal, status, stream_version, opened_at, updated_at)
                    VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 'Legacy case', 'OPEN', 1, NOW(), NOW())
                    """.trimIndent(),
                )
            }
        }
        val current = migrate("latest")
        current.migrate()
        current.validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT goal FROM cases").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("Legacy case")
                }
                statement.executeQuery("SELECT COUNT(*) FROM case_requesters").use { rows ->
                    rows.next()
                    assertThat(rows.getInt(1)).isZero()
                }
            }
        }
        assertThat(current.info().pending()).isEmpty()
    }

    private fun assertRecordedAnswer(answer: JsonNode) {
        assertThat(answer.propertyNames())
            .containsExactlyInAnyOrder("responseId", "answer", "respondedAt", "recordedAt")
        assertThat(answer["responseId"].asString()).isEqualTo(UUID(0, 101).toString())
        assertThat(answer["answer"].asString()).isEqualTo(" <script>answer</script> 😀 ")
        assertThat(Instant.parse(answer["respondedAt"].asString())).isEqualTo(clock.instant().plusSeconds(60))
        assertThat(Instant.parse(answer["recordedAt"].asString())).isEqualTo(clock.instant().plusSeconds(60))
    }

    private fun recordBoundedQuestionHistory(
        tenant: TenantId,
        work: UUID,
        case: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO requester_information_requests
                    (tenant_id, request_id, work_item_id, case_id, ownership_revision, question, requested_at, recorded_at)
                SELECT :tenant, LPAD(TO_HEX(n), 32, '0')::UUID, :work, :case, 1, '<script>question</script>', :at, :at
                FROM GENERATE_SERIES(1, 101) n
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("work", work)
            .param("case", case)
            .param("at", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
        // Include an omitted oldest answer: answer presence/time must not reorder or expand the question page.
        jdbc
            .sql(
                """
                INSERT INTO requester_information_responses
                    (tenant_id, response_id, request_id, answer, responded_at, recorded_at)
                SELECT :tenant, LPAD(TO_HEX(n), 32, '0')::UUID, LPAD(TO_HEX(n), 32, '0')::UUID,
                    ' <script>answer</script> 😀 ', :at, :at
                FROM GENERATE_SERIES(1, 101, 100) n
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("at", clock.instant().plusSeconds(60).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun recordAnsweredQuestion(
        tenant: TenantId,
        work: UUID,
        case: UUID,
        requestId: UUID,
        answer: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO requester_information_requests
                    (tenant_id, request_id, work_item_id, case_id, ownership_revision, question, requested_at)
                VALUES (:tenant, :request, :work, :case, 1, 'Shared question identity', :at)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("request", requestId)
            .param("work", work)
            .param("case", case)
            .param("at", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO requester_information_responses (tenant_id, response_id, request_id, answer, responded_at)
                VALUES (:tenant, :response, :request, :answer, :at)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("response", requestId)
            .param("request", requestId)
            .param("answer", answer)
            .param("at", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun questionWork(): Triple<RunSupervisionDatabaseFixture, UUID, UUID> {
        val tenant = TenantId(UUID.randomUUID())
        val requester = actor(tenant.value, "question-reader")
        val graph =
            requireNotNull(
                transactions.execute {
                    RunSupervisionDatabaseFixture.seed(jdbc, clock.instant(), tenant = tenant)
                },
            )
        val work = UUID.randomUUID()
        transactions.executeWithoutResult {
            // Only the prior escalation graph is synthetic; question and ownership constraints stay enabled.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                    VALUES (:tenant, :work, :run, :event, 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :at)
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("work", work)
                .param("run", graph.run.value)
                .param("event", UUID.randomUUID())
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update()
        }
        claims.claim(tenant.value, work, graph.outgoing.value)
        val case =
            jdbc
                .sql("SELECT case_id FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run")
                .param("tenant", tenant.value)
                .param("run", graph.run.value)
                .query(UUID::class.java)
                .single()
        bind(tenant.value, case, requester)
        return Triple(graph, work, case)
    }

    private fun actor(
        tenant: UUID,
        subject: String,
    ): UUID =
        actors
            .registerActor(tenant, "workforce-sso", subject)
            .actor.id.value

    private fun login(subject: String) =
        oidcLogin()
            .clientRegistration(requireNotNull(registrations.findByRegistrationId("ergon-requester")))
            .idToken { it.issuer("https://identity.example.test").subject(subject) }

    private fun bind(
        tenant: UUID,
        case: UUID,
        actor: UUID,
        source: String = "trusted-test-fixture",
    ) {
        jdbc
            .sql(
                """
                INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
                VALUES (:t, :c, :a, :s)
                """.trimIndent(),
            ).param("t", tenant)
            .param("c", case)
            .param("a", actor)
            .param("s", source)
            .update()
    }

    companion object {
        private const val PATH = "/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/progress"

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean @Primary
        fun requesterProgressClock(): Clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC)
    }
}
