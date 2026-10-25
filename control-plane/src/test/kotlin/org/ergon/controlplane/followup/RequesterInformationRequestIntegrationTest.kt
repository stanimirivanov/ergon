package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.RequesterInformationQuestion
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.identity.domain.HumanActorId
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
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(InformationRequestClockConfiguration::class)
class RequesterInformationRequestIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val requests: RequesterInformationRequestRepository,
    @Autowired manager: PlatformTransactionManager,
) {
    private val template = TransactionTemplate(manager)

    @Test
    fun `first write and replay use database attribution without merging competing intent`() {
        val (tenant, request) = fixture()
        val first =
            record(
                tenant,
                request.copy(
                    claimId = HumanFollowUpClaimId(UUID.randomUUID()),
                    authorActorId = HumanActorId(UUID.randomUUID()),
                    requestedAt = NOW.plusNanos(123),
                ),
            )
        assertThat(first.created).isTrue()
        assertThat(first.stored.request.claimId).isEqualTo(request.claimId)
        assertThat(first.stored.request.authorActorId).isEqualTo(request.authorActorId)
        assertThat(first.stored.request.requestedAt.nano % 1000).isZero()
        val replay = record(tenant, request.copy(question = RequesterInformationQuestion("changed")))
        assertThat(replay.created).isFalse()
        assertThat(replay.stored).isEqualTo(first.stored)
        val (otherTenant, otherRequest) = fixture()
        assertThat(record(otherTenant, otherRequest.copy(requestId = request.requestId)).created).isTrue()
        val (_, otherWork) = fixture(tenant = tenant)
        assertThat(record(tenant, otherWork.copy(requestId = request.requestId)).stored).isEqualTo(first.stored)
    }

    @Test
    fun `concurrent writers return one immutable winner without merging text or timestamps`() {
        val (tenant, request) = fixture()
        val outcomes =
            RunEscalationTestFixture.together(
                { record(tenant, request) },
                {
                    record(
                        tenant,
                        request.copy(
                            question = RequesterInformationQuestion("different"),
                            requestedAt = NOW.plusSeconds(1),
                        ),
                    )
                },
            )
        assertThat(outcomes.count { it.created }).isEqualTo(1)
        assertThat(outcomes.map { it.stored }.distinct()).hasSize(1)
    }

    @Test
    fun `storage requires a same tenant bound source case and historical claimed revision`() {
        val (tenant, request) = fixture()
        val (_, other) = fixture(tenant = tenant)
        val (foreignTenant, _) = fixture()
        val (unboundTenant, unbound) = fixture(bound = false)
        for ((target, invalid) in listOf(
            tenant to request.copy(caseId = other.caseId),
            foreignTenant to request,
            tenant to request.copy(ownershipRevision = HumanFollowUpOwnershipRevision(99)),
            unboundTenant to unbound,
        )) {
            assertThatThrownBy { record(target, invalid) }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `storage rejects Unicode blank questions source mismatch and audit mutation`() {
        val (tenant, request) = fixture()
        record(tenant, request)
        val blanks = (Char.MIN_VALUE..Char.MAX_VALUE).filter(Char::isWhitespace).map(Char::toString)
        for (question in blanks + listOf("x".repeat(4001))) {
            assertThatThrownBy {
                jdbc
                    .sql(
                        """
                        INSERT INTO requester_information_requests
                            (tenant_id, request_id, work_item_id, case_id, ownership_revision, question, requested_at)
                        SELECT tenant_id, :id, work_item_id, case_id, ownership_revision, :question, requested_at
                        FROM requester_information_requests WHERE tenant_id = :tenant
                        """.trimIndent(),
                    ).param("id", UUID.randomUUID())
                    .param("question", question)
                    .param("tenant", tenant.value)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    INSERT INTO requester_information_requests
                        (tenant_id, request_id, work_item_id, case_id, ownership_revision, question, requested_at)
                    SELECT tenant_id, :id, work_item_id, :case, ownership_revision, question, requested_at
                    FROM requester_information_requests WHERE tenant_id = :tenant
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("case", UUID.randomUUID())
                .param("tenant", tenant.value)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        for (sql in listOf(
            "UPDATE requester_information_requests SET question = question",
            "DELETE FROM requester_information_requests",
            "TRUNCATE requester_information_requests",
        )) {
            assertThatThrownBy { jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(record(tenant, request).created).isFalse()
    }

    @Test
    fun `released schema upgrade preserves existing cases and adds no implicit requests`() {
        val schema = "information_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway() =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
        flyway().target("20261025051735").load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO cases (tenant_id, case_id, goal, status, stream_version, opened_at, updated_at)
                    VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 'retained', 'OPEN', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
            }
        }
        flyway().load().migrate()
        flyway().load().validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM cases WHERE goal = 'retained'").use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getInt(1)).isEqualTo(1)
                }
                statement.executeQuery("SELECT COUNT(*) FROM requester_information_requests").use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getInt(1)).isZero()
                }
            }
        }
    }

    private fun fixture(
        bound: Boolean = true,
        tenant: TenantId = TenantId(UUID.randomUUID()),
    ): Pair<TenantId, RequesterInformationRequest> {
        val graph = requireNotNull(template.execute { RunSupervisionDatabaseFixture.seed(jdbc, NOW, tenant = tenant) })
        val work = UUID.randomUUID()
        template.executeWithoutResult {
            // Only the prior escalation source graph is synthetic; new request/claim constraints stay enabled.
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
            if (bound) {
                jdbc
                    .sql(
                        """
                        INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
                        SELECT tenant_id, case_id, :actor, 'synthetic reviewed binding'
                        FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run
                        """.trimIndent(),
                    ).param("tenant", graph.tenant.value)
                    .param("run", graph.run.value)
                    .param("actor", graph.incoming.value)
                    .update()
            }
        }
        val claim = claims.claim(graph.tenant.value, work, graph.outgoing.value).storedClaim.claim
        val caseId =
            jdbc
                .sql("SELECT case_id FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run")
                .param("tenant", tenant.value)
                .param("run", graph.run.value)
                .query(UUID::class.java)
                .single()
        return tenant to
            RequesterInformationRequest(
                RequesterInformationRequestId(UUID.randomUUID()),
                HumanFollowUpWorkItemId(work),
                CaseId(caseId),
                claim.id,
                HumanFollowUpOwnershipRevision(1),
                claim.resolverActorId,
                RequesterInformationQuestion(" <script>question</script> "),
                NOW,
            )
    }

    private fun record(
        tenant: TenantId,
        request: RequesterInformationRequest,
    ) = requireNotNull(template.execute { requests.record(tenant, request) })

    companion object {
        val NOW: Instant = Instant.parse("2026-10-25T14:00:00Z")

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
private class InformationRequestClockConfiguration {
    @Bean @Primary
    fun informationRequestClock(): Clock = Clock.fixed(RequesterInformationRequestIntegrationTest.NOW, ZoneOffset.UTC)
}
