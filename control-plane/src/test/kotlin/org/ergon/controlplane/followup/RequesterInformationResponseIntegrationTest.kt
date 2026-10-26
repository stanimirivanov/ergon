package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.RequesterInformationResponse
import org.ergon.controlplane.followup.application.RequesterInformationResponseRepository
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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(InformationResponseClockConfiguration::class)
class RequesterInformationResponseIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val questions: RequesterInformationRequestRepository,
    @Autowired private val responses: RequesterInformationResponseRepository,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

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

    private fun question(tenant: TenantId = TenantId(UUID.randomUUID())): Pair<TenantId, RequesterInformationRequest> =
        requireNotNull(
            transactions.execute {
                val graph = RunSupervisionDatabaseFixture.seed(jdbc, NOW, tenant = tenant)
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
                    .param("actor", graph.incoming.value)
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
