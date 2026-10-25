package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.followup.application.OwnedInformationRequestsReader
import org.ergon.controlplane.followup.application.OwnedInformationRequestsService
import org.ergon.controlplane.followup.application.RequesterCaseWriteGuard
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestCommand
import org.ergon.controlplane.followup.application.RequesterInformationRequestConflictException
import org.ergon.controlplane.followup.application.RequesterInformationRequestNotFoundException
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.RequesterInformationRequestService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.follow-up.release-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(InformationRequestClockConfiguration::class, BrowserSessionTestClientConfiguration::class)
// Real ownership, database transactions and HTTP security are independent verification boundaries.
@Suppress("LongParameterList")
class RequesterInformationRequestIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val requests: RequesterInformationRequestRepository,
    @Autowired private val service: RequesterInformationRequestService,
    @Autowired private val releases: HumanFollowUpReleaseService,
    @Autowired private val claimRepository: HumanFollowUpClaimRepository,
    @Autowired private val guard: RequesterCaseWriteGuard,
    @Autowired private val history: OwnedInformationRequestsService,
    @Autowired private val historyReader: OwnedInformationRequestsReader,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired manager: PlatformTransactionManager,
) {
    private val template = TransactionTemplate(manager)

    @Test
    fun `owner history stays bounded across authors and is withdrawn after release or case closure`() {
        val command = command()
        val receipt = service.record(command).stored
        mvc.perform(get(path(command))).andExpect(status().isUnauthorized)
        val json =
            mapper.readTree(
                mvc
                    .perform(get(path(command)).with(RunEscalationTestFixture.session(command.actorId)))
                    .andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(json["items"][0]["requestId"].asText()).isEqualTo(command.requestId.toString())
        assertThat(json["truncated"].asBoolean()).isFalse()
        releases.release(
            HumanFollowUpReleaseCommand(command.tenantId, command.workItemId, command.claimId, command.actorId, 1),
        )
        mvc
            .perform(get(path(command)).with(RunEscalationTestFixture.session(command.actorId)))
            .andExpect(status().isNotFound)
        val nextActor =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant")
                .param("tenant", command.tenantId)
                .query(UUID::class.java)
                .single()
        val successor = claims.claim(command.tenantId, command.workItemId, nextActor)
        assertThat(successor.storedClaim.claim.id.value).isNotEqualTo(command.claimId)
        assertThat(history.list(command.tenantId, command.workItemId, nextActor).items).containsExactly(receipt)
        for ((tenant, work, actor) in listOf(
            Triple(command.tenantId, command.workItemId, command.actorId),
            Triple(UUID.randomUUID(), command.workItemId, nextActor),
            Triple(command.tenantId, UUID.randomUUID(), nextActor),
        )) {
            assertThatThrownBy { history.list(tenant, work, actor) }
                .isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
        }
        jdbc
            .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :tenant")
            .param("tenant", command.tenantId)
            .update()
        mvc
            .perform(get(path(command)).with(RunEscalationTestFixture.session(nextActor)))
            .andExpect(status().isNotFound)
        val unbound = command(bound = false)
        mvc
            .perform(get(path(unbound)).with(RunEscalationTestFixture.session(unbound.actorId)))
            .andExpect(status().isNotFound)
        val requesterPath = "/bff/v1/tenants/${command.tenantId}/requester-cases/${receipt.request.caseId.value}"
        mvc
            .perform(
                get("$requesterPath/information-requests")
                    .with(RunEscalationTestFixture.session(command.actorId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `owner authority is rechecked after case and history waits before disclosure`() {
        for (atCase in listOf(true, false)) {
            val command = command()
            service.record(command)
            val time = AtomicReference(NOW)
            val clock =
                object : Clock() {
                    override fun instant(): Instant = time.get()

                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = error("unused by read")
                }
            val delayedCase =
                RequesterCaseWriteGuard { tenant, case ->
                    guard.lockOpenBoundCase(tenant, case).also { if (atCase) time.set(NOW.plusSeconds(3601)) }
                }
            val delayedHistory =
                OwnedInformationRequestsReader { tenant, work, limit ->
                    historyReader.latest(tenant, work, limit).also { time.set(NOW.plusSeconds(3601)) }
                }
            val reader =
                OwnedInformationRequestsService(
                    claimRepository,
                    delayedCase,
                    delayedHistory,
                    transactions,
                    clock,
                )
            assertThatThrownBy { reader.list(command.tenantId, command.workItemId, command.actorId) }
                .isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
        }
    }

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

    @Test
    fun `session CSRF and current owner gates protect exact immutable browser receipts`() {
        val command = command()
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
        val created = send(command, 201)
        assertThat(send(command, 200)).isEqualTo(created)
        val receipt = mapper.readTree(created)
        assertThat(receipt["authorActorId"].asString()).isEqualTo(command.actorId.toString())
        assertThat(receipt["question"].asString()).isEqualTo(command.question)
        assertThat(created).doesNotContain("subject", "requesterActorId", "authorityEvidenceId", "sourceReference")
        send(command.copy(question = "changed"), 409)
        send(command.copy(expectedOwnershipRevision = 2), 409)
        send(command.copy(expectedOwnershipRevision = 0), 400)
        send(command.copy(question = "\u00A0"), 400)
        assertThat(count(command)).isEqualTo(1)
        val otherWork = command(tenant = TenantId(command.tenantId)).copy(requestId = command.requestId)
        send(otherWork, 409)
        assertThat(count(command)).isEqualTo(1)
    }

    @Test
    fun `hidden work unbound and closed cases share a non disclosing failure even on replay`() {
        val command = command()
        val unbound = command(bound = false)
        val otherActor =
            jdbc
                .sql("SELECT actor_id FROM human_actors WHERE tenant_id = :tenant AND actor_id <> :actor LIMIT 1")
                .param("tenant", command.tenantId)
                .param("actor", command.actorId)
                .query(UUID::class.java)
                .single()
        val hidden = command.copy(actorId = otherActor)
        val failures =
            listOf(command.copy(workItemId = UUID.randomUUID()), unbound, hidden)
                .map { mapper.readTree(send(it, 404)) }
        assertThat(failures.map { listOf(it["type"], it["detail"]) }.distinct()).hasSize(1)
        send(command, 201)
        jdbc
            .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :tenant")
            .param("tenant", command.tenantId)
            .update()
        assertThatThrownBy { service.record(command) }
            .isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
        assertThat(count(unbound)).isZero()
    }

    @Test
    fun `concurrent exact requests record once while competing text conflicts without merging`() {
        val command = command()
        val exact = RunEscalationTestFixture.together({ service.record(command) }, { service.record(command) })
        assertThat(exact.count { it.created }).isEqualTo(1)
        assertThat(exact.map { it.stored }.distinct()).hasSize(1)
        val fresh = command.copy(requestId = UUID.randomUUID())
        val different =
            RunEscalationTestFixture.together(
                { runCatching { service.record(fresh) } },
                { runCatching { service.record(fresh.copy(question = "different intent")) } },
            )
        assertThat(different.count { it.isSuccess }).isEqualTo(1)
        assertThat(different.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(RequesterInformationRequestConflictException::class.java)
        assertThat(count(command)).isEqualTo(2)
    }

    @Test
    fun `release serializes with recording and prevents later receipt recovery`() {
        val command = command()
        val outcomes =
            RunEscalationTestFixture.together(
                { runCatching { service.record(command) } },
                {
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
                    }
                },
            )
        assertThat(outcomes[1].isSuccess).isTrue()
        outcomes[0].exceptionOrNull()?.let {
            assertThat(it).isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
        }
        assertThatThrownBy { service.record(command) }
            .isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
    }

    @Test
    fun `authority expiry during case or identity waits rolls back before acknowledgement`() {
        for (waitAtCase in listOf(true, false)) {
            val command = command()
            val time = AtomicReference(NOW)
            val clock =
                object : Clock() {
                    override fun instant(): Instant = time.get()

                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = error("unused by command")
                }
            val delayedCase =
                RequesterCaseWriteGuard { tenant, case ->
                    guard.lockOpenBoundCase(tenant, case).also {
                        if (waitAtCase) time.set(NOW.plusSeconds(3601))
                    }
                }
            val delayedStorage =
                RequesterInformationRequestRepository { tenant, request ->
                    requests.record(tenant, request).also { time.set(NOW.plusSeconds(3601)) }
                }
            val delayed =
                RequesterInformationRequestService(
                    claimRepository,
                    delayedCase,
                    delayedStorage,
                    transactions,
                    clock,
                )
            assertThatThrownBy { delayed.record(command) }
                .isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
            assertThat(count(command)).isZero()
        }
    }

    private fun command(
        bound: Boolean = true,
        tenant: TenantId = TenantId(UUID.randomUUID()),
    ): RequesterInformationRequestCommand {
        val (_, request) = fixture(bound, tenant)
        return RequesterInformationRequestCommand(
            tenant.value,
            request.workItemId.value,
            request.authorActorId.value,
            request.requestId.value,
            request.claimId.value,
            request.ownershipRevision.value,
            request.question.value,
        )
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

    private fun path(command: RequesterInformationRequestCommand) =
        "/bff/v1/tenants/${command.tenantId}/human-follow-ups/${command.workItemId}/information-requests"

    private fun body(command: RequesterInformationRequestCommand) =
        mapper.writeValueAsString(
            mapOf(
                "requestId" to command.requestId,
                "claimId" to command.claimId,
                "expectedOwnershipRevision" to command.expectedOwnershipRevision,
                "question" to command.question,
            ),
        )

    private fun send(
        command: RequesterInformationRequestCommand,
        expectedStatus: Int,
    ): String =
        mvc
            .perform(
                post(path(command))
                    .with(RunEscalationTestFixture.session(command.actorId))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().`is`(expectedStatus))
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andReturn()
            .response.contentAsString

    private fun count(command: RequesterInformationRequestCommand) =
        jdbc
            .sql("SELECT COUNT(*) FROM requester_information_requests WHERE tenant_id = :tenant")
            .param("tenant", command.tenantId)
            .query(Long::class.java)
            .single()

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
