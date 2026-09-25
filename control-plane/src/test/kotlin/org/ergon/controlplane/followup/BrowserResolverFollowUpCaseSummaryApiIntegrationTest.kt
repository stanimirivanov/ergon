package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineEntry
import org.ergon.controlplane.cases.application.PinnedResolutionContract
import org.ergon.controlplane.cases.application.TimelineObservation
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.ResolverFollowUpCaseSummary
import org.ergon.controlplane.followup.application.ResolverFollowUpCaseSummaryNotFoundException
import org.ergon.controlplane.followup.application.ResolverFollowUpCaseSummaryService
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpWork
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpSource
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class)
class BrowserResolverFollowUpCaseSummaryApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val claims: HumanFollowUpClaimRepository,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @MockitoBean
    private lateinit var summaries: ResolverFollowUpCaseSummaryService

    @Test
    fun `returns case evidence and run context without private attribution`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val summary = summary(actorId)
        Mockito
            .`when`(summaries.get(tenantId, WORK_ITEM_ID.value, actorId))
            .thenReturn(summary)

        mockMvc
            .perform(get(SUMMARY_PATH, tenantId, WORK_ITEM_ID.value).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(jsonPath("$.followUp.workItemId").value(WORK_ITEM_ID.value.toString()))
            .andExpect(jsonPath("$.followUp.queueKey").value("access-restoration"))
            .andExpect(jsonPath("$.followUp.escalationReason").value("RETRY_ATTEMPT_LIMIT_REACHED"))
            .andExpect(jsonPath("$.case.caseId").value(CASE_ID.value.toString()))
            .andExpect(jsonPath("$.case.goal").value("Restore workspace access"))
            .andExpect(jsonPath("$.case.resolutionContract.key").value("restore-workspace-access"))
            .andExpect(jsonPath("$.case.resolutionContract.revision").value(1))
            .andExpect(jsonPath("$.observations[0].observationId").value(OBSERVATION_ID.toString()))
            .andExpect(jsonPath("$.observations[0].originType").value("CONNECTOR"))
            .andExpect(jsonPath("$.observations[0].provider").value("identity-directory"))
            .andExpect(jsonPath("$.observations[0].reference").value("accounts/employee-42"))
            .andExpect(jsonPath("$.observations[0].content").value("Account is locked"))
            .andExpect(jsonPath("$.resolutionRun.runId").value(RUN_ID.value.toString()))
            .andExpect(jsonPath("$.resolutionRun.state").value("ESCALATED"))
            .andExpect(jsonPath("$.resolutionRun.requiredApproval").value("RESOLVER"))
            .andExpect(jsonPath("$.followUp.resolverActorId").doesNotExist())
            .andExpect(jsonPath("$.followUp.authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.idToken").doesNotExist())

        Mockito.verify(summaries).get(tenantId, WORK_ITEM_ID.value, actorId)
    }

    @Test
    fun `requires an authenticated browser session before reading case context`() {
        mockMvc
            .perform(get(SUMMARY_PATH, UUID.randomUUID(), WORK_ITEM_ID.value))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))

        Mockito.verifyNoInteractions(summaries)
    }

    @Test
    fun `does not disclose case context through a tenant-foreign actor binding`() {
        registerActor(UUID.randomUUID())

        mockMvc
            .perform(get(SUMMARY_PATH, UUID.randomUUID(), WORK_ITEM_ID.value).with(verifiedSession()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))

        Mockito.verifyNoInteractions(summaries)
    }

    @Test
    fun `returns one stable absence response for hidden follow-up context`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito
            .`when`(summaries.get(tenantId, WORK_ITEM_ID.value, actorId))
            .thenThrow(ResolverFollowUpCaseSummaryNotFoundException(WORK_ITEM_ID.value))

        mockMvc
            .perform(get(SUMMARY_PATH, tenantId, WORK_ITEM_ID.value).with(verifiedSession()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolver-follow-up-case-summary-not-found"))
            .andExpect(
                jsonPath("$.instance").value(
                    "/bff/v1/tenants/$tenantId/human-follow-ups/${WORK_ITEM_ID.value}/case-summary",
                ),
            )
    }

    @Test
    @Transactional
    fun `persistent ownership lookup hides other resolvers and expired authority`() {
        val tenantId = TenantId(UUID.randomUUID())
        val actorId = HumanActorId(UUID.randomUUID())
        seedOwnedWork(tenantId, actorId)

        val visible = claims.findOwnedWorkForResolver(tenantId, WORK_ITEM_ID, actorId, NOW)

        assertThat(visible).isNotNull
        assertThat(visible?.workItem?.item?.caseId).isEqualTo(CASE_ID)
        assertThat(visible?.claim?.claim?.resolverActorId).isEqualTo(actorId)
        assertThat(
            claims.findOwnedWorkForResolver(
                tenantId,
                WORK_ITEM_ID,
                HumanActorId(UUID.randomUUID()),
                NOW,
            ),
        ).isNull()
        assertThat(claims.findOwnedWorkForResolver(tenantId, WORK_ITEM_ID, actorId, NOW.plusSeconds(61)))
            .isNull()
    }

    private fun verifiedSession() =
        oidcLogin().idToken {
            it.issuer(TRUSTED_ISSUER)
            it.subject(SUBJECT)
        }

    private fun registerActor(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(
                    post(ACTORS_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTOR_REQUEST),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun seedOwnedWork(
        tenantId: TenantId,
        actorId: HumanActorId,
    ) {
        val claimId = UUID.randomUUID()
        val authorityEvidenceId = UUID.randomUUID()
        // This fixture isolates the adapter query; earlier migrations prove the
        // foreign-key graph that normally creates these durable records.
        jdbcClient.sql("SET LOCAL session_replication_role = replica").update()
        seedRun(tenantId)
        seedWorkItem(tenantId)
        seedAuthority(tenantId, actorId, authorityEvidenceId)
        seedClaim(tenantId, actorId, authorityEvidenceId, claimId)
    }

    private fun seedRun(tenantId: TenantId) {
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_runs (
                    tenant_id, run_id, case_id, case_stream_version,
                    contract_key, contract_revision, policy_revision,
                    step_id, capability, effective_risk, required_approval,
                    initial_state
                ) VALUES (
                    :tenantId, :runId, :caseId, 4,
                    'restore-workspace-access', 1, 'ergon.dev/policy/access-restoration/v1',
                    'unlock-account', 'identity.account.unlock', 'HIGH', 'RESOLVER',
                    'WAITING_FOR_APPROVAL'
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", RUN_ID.value)
            .param("caseId", CASE_ID.value)
            .update()
    }

    private fun seedWorkItem(tenantId: TenantId) {
        jdbcClient
            .sql(
                """
                INSERT INTO human_follow_up_work_items (
                    tenant_id, work_item_id, run_id, escalation_event_id,
                    reason, status, opened_at
                ) VALUES (
                    :tenantId, :workItemId, :runId, :escalationEventId,
                    'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :openedAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("workItemId", WORK_ITEM_ID.value)
            .param("runId", RUN_ID.value)
            .param("escalationEventId", ESCALATION_EVENT_ID.value)
            .param("openedAt", NOW.minusSeconds(30).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun seedAuthority(
        tenantId: TenantId,
        actorId: HumanActorId,
        authorityEvidenceId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO approval_authority_evidence (
                    tenant_id, evidence_id, actor_id, authority, case_id,
                    source_provider, source_reference, attested_at, expires_at
                ) VALUES (
                    :tenantId, :evidenceId, :actorId, 'RESOLVER', NULL,
                    'workforce-sso', 'groups/resolvers', :attestedAt, :expiresAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("evidenceId", authorityEvidenceId)
            .param("actorId", actorId.value)
            .param("attestedAt", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))
            .param("expiresAt", NOW.plusSeconds(60).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun seedClaim(
        tenantId: TenantId,
        actorId: HumanActorId,
        authorityEvidenceId: UUID,
        claimId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO human_follow_up_claims (
                    tenant_id, claim_id, work_item_id, resolver_actor_id,
                    authority_evidence_id, claimed_at
                ) VALUES (
                    :tenantId, :claimId, :workItemId, :actorId,
                    :authorityEvidenceId, :claimedAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("claimId", claimId)
            .param("workItemId", WORK_ITEM_ID.value)
            .param("actorId", actorId.value)
            .param("authorityEvidenceId", authorityEvidenceId)
            .param("claimedAt", NOW.minusSeconds(20).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun summary(actorId: UUID) =
        ResolverFollowUpCaseSummary(
            ownedWork(actorId),
            caseTimeline(),
            storedRun(),
            ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ESCALATED, 2, NOW),
        )

    private fun ownedWork(actorId: UUID): ResolverOwnedHumanFollowUpWork {
        val evidence =
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                HumanActorId(actorId),
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
            )
        val item =
            HumanFollowUpWorkItem.open(
                WORK_ITEM_ID,
                HumanFollowUpSource(CASE_ID, RUN_ID, ESCALATION_EVENT_ID, REASON),
                HumanFollowUpQueueKey.ACCESS_RESTORATION,
                NOW.minusSeconds(30),
            )
        val claim =
            HumanFollowUpClaim.claim(
                HumanFollowUpClaimId(UUID.randomUUID()),
                WORK_ITEM_ID,
                evidence,
                NOW.minusSeconds(20),
            )
        return ResolverOwnedHumanFollowUpWork(
            StoredHumanFollowUpWorkItem(item, NOW.minusSeconds(29)),
            StoredHumanFollowUpClaim(claim, NOW.minusSeconds(19)),
        )
    }

    private fun caseTimeline(): CaseTimeline {
        val contract =
            PinnedResolutionContract(
                "restore-workspace-access",
                1,
                3,
                NOW.minusSeconds(150),
                NOW.minusSeconds(149),
            )
        val observation =
            TimelineObservation(
                OBSERVATION_ID,
                "CONNECTOR",
                "identity-directory",
                "accounts/employee-42",
                "Account is locked",
            )
        val entry =
            CaseTimelineEntry(
                4,
                "CONNECTOR_OBSERVATION_RECORDED",
                "Connector observation recorded",
                NOW.minusSeconds(140),
                NOW.minusSeconds(139),
                observation,
            )
        return CaseTimeline(CASE_ID.value, "Restore workspace access", "OPEN", 4, contract, listOf(entry))
    }

    private fun storedRun(): StoredResolutionRunStart {
        val plan =
            ResolutionRunPlan(
                4,
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-workspace-access"),
                    ResolutionContractRevision.of(1),
                ),
                ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                ResolutionStepId.of("unlock-account"),
                CapabilityName.of("identity.account.unlock"),
                StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.RESOLVER),
            )
        return StoredResolutionRunStart(
            ResolutionRunStart.create(RUN_ID, CASE_ID, plan),
            NOW.minusSeconds(120),
        )
    }

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val SUMMARY_PATH =
            "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val ACTOR_REQUEST =
            """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
        private val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        private val CASE_ID = CaseId(UUID.randomUUID())
        private val RUN_ID = ResolutionRunId(UUID.randomUUID())
        private val ESCALATION_EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        private val OBSERVATION_ID = UUID.randomUUID()
        private val REASON = ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED
        private val NOW = Instant.parse("2026-09-25T12:00:00Z")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
