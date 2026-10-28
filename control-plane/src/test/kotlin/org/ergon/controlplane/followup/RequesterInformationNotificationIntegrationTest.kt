package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionCommand
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionService
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverCommand
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverService
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.RequesterInformationResponse
import org.ergon.controlplane.followup.application.RequesterInformationResponseRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpHandover
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.followup.domain.RequesterInformationAnswer
import org.ergon.followup.domain.RequesterInformationResponseId
import org.ergon.identity.domain.TenantId
import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.security.browser-session.audience=workbench",
        "ergon.follow-up.release-enabled=true",
        "ergon.follow-up.handover-enabled=true",
        "ergon.follow-up.completion-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@Import(InformationResponseClockConfiguration::class, BrowserSessionTestClientConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// Delivery and transaction evidence is separate from the HTTP/answer protocol suite.
@Suppress("LongParameterList")
class RequesterInformationNotificationIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val questions: RequesterInformationRequestRepository,
    @Autowired private val responses: RequesterInformationResponseRepository,
    @Autowired private val releases: HumanFollowUpReleaseService,
    @Autowired private val handovers: HumanFollowUpHandoverService,
    @Autowired private val completions: HumanFollowUpCompletionService,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)
    private val informationFixture = RequesterInformationDatabaseFixture(jdbc, claims, questions, transactions, NOW)

    @Test
    fun `workbench feed scopes recipient while historical alerts never restore target access`() {
        val (tenant, question) = question()
        val transferred = handoverToRequester(tenant, question)
        val recipient = transferred.command.recipientActorId
        record(tenant, response(question))
        val url = "/bff/v1/tenants/${tenant.value}/notifications"
        mvc
            .perform(get(url).with(notificationSession(question.authorActorId.value)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        val kinds = containsInAnyOrder("INFORMATION_ANSWERED", "FOLLOW_UP_HANDED_OVER")
        mvc
            .perform(
                get(url)
                    .with(notificationSession(recipient))
                    .param("actorId", question.authorActorId.value.toString()),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[*].kind").value(kinds))
            .andExpect(jsonPath("$.items[0].target.kind").value("OWNED_HUMAN_FOLLOW_UP"))
            .andExpect(jsonPath("$.items[0].target.id").value(question.workItemId.value.toString()))
            .andExpect(jsonPath("$.items[0].answer").doesNotExist())
            .andExpect(jsonPath("$.items[0].recipientActorId").doesNotExist())
        releases.release(
            HumanFollowUpReleaseCommand(
                tenant.value,
                question.workItemId.value,
                transferred.successorClaimId.value,
                recipient,
                transferred.ownershipRevision.value,
            ),
        )
        mvc
            .perform(get(url).with(notificationSession(recipient)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
        mvc
            .perform(
                get("/bff/v1/tenants/${tenant.value}/human-follow-ups/${question.workItemId.value}/case-summary")
                    .with(notificationSession(recipient)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `workbench feed rejects unknown actors foreign tenants bearer and other browser clients`() {
        val (tenant, question) = question()
        val actor = question.authorActorId.value
        val url = "/bff/v1/tenants/${tenant.value}/notifications"
        mvc.perform(get(url)).andExpect(status().isUnauthorized)
        mvc.perform(get(url).with(jwt())).andExpect(status().isForbidden)
        mvc.perform(get(url).with(notificationSession(UUID.randomUUID()))).andExpect(status().isForbidden)
        mvc
            .perform(get("/bff/v1/tenants/${UUID.randomUUID()}/notifications").with(notificationSession(actor)))
            .andExpect(status().isForbidden)
        val requesterClient =
            BrowserSessionTestClientConfiguration()
                .browserClientRegistrationRepository("requester")
                .findByRegistrationId("ergon-requester")
        val wrongSession =
            RunEscalationTestFixture.session(actor).clientRegistration(requireNotNull(requesterClient))
        mvc
            .perform(get(url).with(wrongSession))
            .andExpect(status().isForbidden)
    }

    /** This synthetic bound requester also has resolver authority, testing both audience roles on one actor. */
    private fun handoverToRequester(
        tenant: TenantId,
        question: RequesterInformationRequest,
    ): StoredHumanFollowUpHandover {
        val recipient =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant")
                .param("tenant", tenant.value)
                .query(UUID::class.java)
                .single()
        return handovers
            .handOver(
                HumanFollowUpHandoverCommand(
                    tenant.value,
                    question.workItemId.value,
                    question.authorActorId.value,
                    UUID.randomUUID(),
                    question.claimId.value,
                    question.ownershipRevision.value,
                    recipient,
                ),
            ).receipt
    }

    private fun notificationSession(actor: UUID) =
        RunEscalationTestFixture.session(actor).clientRegistration(
            requireNotNull(
                BrowserSessionTestClientConfiguration()
                    .browserClientRegistrationRepository("workbench")
                    .findByRegistrationId("ergon-workbench"),
            ),
        )

    @Test
    fun `question and answer deliveries roll back with their sources`() {
        val tenant = TenantId(UUID.randomUUID())
        transactions.executeWithoutResult { transaction ->
            val (_, question) = question(tenant)
            record(tenant, response(question))
            assertThat(deliveryCount("requester_information_request_notifications", tenant)).isEqualTo(1)
            assertThat(deliveryCount("requester_information_response_notifications", tenant)).isEqualTo(1)
            transaction.setRollbackOnly()
        }
        for (table in listOf(
            "requester_information_requests",
            "requester_information_responses",
            "requester_information_request_notifications",
            "requester_information_response_notifications",
        )) {
            assertThat(deliveryCount(table, tenant)).isZero()
        }
    }

    @Test
    fun `question addresses requester and answer pins successor rather than original question author`() {
        val (tenant, question) = question()
        val recipient =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant")
                .param("tenant", tenant.value)
                .query(UUID::class.java)
                .single()
        assertThat(deliveryCount("requester_information_request_notifications", tenant)).isEqualTo(1)
        val transfer =
            handovers
                .handOver(
                    HumanFollowUpHandoverCommand(
                        tenant.value,
                        question.workItemId.value,
                        question.authorActorId.value,
                        UUID.randomUUID(),
                        question.claimId.value,
                        question.ownershipRevision.value,
                        recipient,
                    ),
                ).receipt
        val intent = response(question)
        record(tenant, intent)
        assertThat(answerRecipient(tenant, intent)).isEqualTo(recipient)
        assertThat(recipient).isNotEqualTo(question.authorActorId.value)
        releases.release(
            HumanFollowUpReleaseCommand(
                tenant.value,
                question.workItemId.value,
                transfer.successorClaimId.value,
                recipient,
                transfer.ownershipRevision.value,
            ),
        )
        assertThat(record(tenant, intent).created).isFalse()
        assertThat(answerRecipient(tenant, intent)).isEqualTo(recipient)
    }

    @Test
    fun `completed work suppresses resolver delivery without rejecting the answer`() {
        val (tenant, question) = question()
        completions.complete(
            HumanFollowUpCompletionCommand(
                tenant.value,
                question.workItemId.value,
                question.authorActorId.value,
                UUID.randomUUID(),
                question.claimId.value,
                question.ownershipRevision.value,
            ),
        )
        assertThat(record(tenant, response(question)).created).isTrue()
        assertThat(deliveryCount("requester_information_response_notifications", tenant)).isZero()
    }

    @Test
    fun `answer selection does not wait on work and retains precommit owner across a racing release`() {
        val (tenant, question) = question()
        val intent = response(question)
        Executors.newSingleThreadExecutor().use { executor ->
            transactions.executeWithoutResult {
                releases.release(
                    HumanFollowUpReleaseCommand(
                        tenant.value,
                        question.workItemId.value,
                        question.claimId.value,
                        question.authorActorId.value,
                        question.ownershipRevision.value,
                    ),
                )
                // The uncommitted release holds work/ownership locks. Delivery may read
                // the previous epoch, but must not invert the answer's case/work order.
                assertThat(executor.submit<Boolean> { record(tenant, intent).created }.get(20, TimeUnit.SECONDS))
                    .isTrue()
            }
        }
        assertThat(answerRecipient(tenant, intent)).isEqualTo(question.authorActorId.value)
        assertThat(record(tenant, intent).created).isFalse()
        assertThat(deliveryCount("requester_information_response_notifications", tenant)).isEqualTo(1)
    }

    @Test
    fun `information deliveries reject mutation foreign tenants and nonexistent recipient epochs`() {
        val (tenant, question) = question()
        val intent = response(question)
        record(tenant, intent)
        val tables =
            listOf("requester_information_request_notifications", "requester_information_response_notifications")
        for (table in tables) {
            val mutations =
                listOf("UPDATE $table SET delivered_at = delivered_at", "DELETE FROM $table", "TRUNCATE $table")
            for (sql in mutations) {
                assertThatThrownBy { jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
            }
        }
        releases.release(
            HumanFollowUpReleaseCommand(
                tenant.value,
                question.workItemId.value,
                question.claimId.value,
                question.authorActorId.value,
                question.ownershipRevision.value,
            ),
        )
        assertThatThrownBy {
            jdbc
                .sql(
                    """
                    INSERT INTO requester_information_request_notifications (tenant_id, request_id)
                    VALUES (:tenant, :request)
                    """.trimIndent(),
                ).param("tenant", UUID.randomUUID())
                .param("request", question.requestId.value)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        for ((candidateTenant, revision) in listOf(tenant.value to 999, tenant.value to 2, UUID.randomUUID() to 1)) {
            val failure =
                runCatching {
                    jdbc
                        .sql(
                            """
                            INSERT INTO requester_information_response_notifications (
                                tenant_id, response_id, recipient_ownership_revision
                            ) VALUES (:tenant, :response, :revision)
                            """.trimIndent(),
                        ).param("tenant", candidateTenant)
                        .param("response", intent.responseId.value)
                        .param("revision", revision)
                        .update()
                }.exceptionOrNull() as? DataAccessException
            assertThat((failure?.mostSpecificCause as? SQLException)?.sqlState).isEqualTo("23503")
        }
    }

    @Test
    fun `notification upgrade does not backfill or retarget historical question and answer replays`() {
        val schema = "information_delivery_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway() =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
        flyway().target("20261028090000").load().migrate()
        lateinit var retained: Pair<TenantId, RequesterInformationRequest>
        lateinit var intent: RequesterInformationResponse
        transactions.executeWithoutResult {
            jdbc.sql("SET LOCAL search_path TO $schema").update()
            retained = question()
            intent = response(retained.second)
            record(retained.first, intent)
        }
        flyway().load().migrate()
        flyway().load().validate()
        transactions.executeWithoutResult {
            jdbc.sql("SET LOCAL search_path TO $schema").update()
            val (tenant, question) = retained
            assertThat(questions.record(tenant, question).created).isFalse()
            assertThat(record(tenant, intent).created).isFalse()
            assertThat(deliveryCount("requester_information_request_notifications", tenant)).isZero()
            assertThat(deliveryCount("requester_information_response_notifications", tenant)).isZero()
            val (_, fresh) = this.question(tenant)
            assertThat(record(tenant, response(fresh)).created).isTrue()
            assertThat(deliveryCount("requester_information_request_notifications", tenant)).isEqualTo(1)
            assertThat(deliveryCount("requester_information_response_notifications", tenant)).isEqualTo(1)
        }
    }

    private fun deliveryCount(
        table: String,
        tenant: TenantId,
    ): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
            .param("tenant", tenant.value)
            .query(Long::class.java)
            .single()

    private fun answerRecipient(
        tenant: TenantId,
        intent: RequesterInformationResponse,
    ): UUID =
        jdbc
            .sql(
                """
                SELECT e.resolver_actor_id FROM requester_information_response_notifications n
                JOIN requester_information_responses r ON r.tenant_id = n.tenant_id AND r.response_id = n.response_id
                JOIN requester_information_requests q ON q.tenant_id = r.tenant_id AND q.request_id = r.request_id
                JOIN human_follow_up_ownership_events e ON e.tenant_id = q.tenant_id AND e.work_item_id = q.work_item_id
                    AND e.ownership_revision = n.recipient_ownership_revision
                WHERE n.tenant_id = :tenant AND n.response_id = :response
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("response", intent.responseId.value)
            .query(UUID::class.java)
            .single()

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
    ): Pair<TenantId, RequesterInformationRequest> = informationFixture.question(tenant, requesterWithoutAuthority)

    companion object {
        val NOW: Instant = RequesterInformationDatabaseFixture.NOW

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
