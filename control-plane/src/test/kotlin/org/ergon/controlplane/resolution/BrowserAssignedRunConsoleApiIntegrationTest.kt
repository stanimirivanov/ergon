package org.ergon.controlplane.resolution

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunDetail
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.AssignedRunOverview
import org.ergon.controlplane.resolution.application.AssignedRunPage
import org.ergon.controlplane.resolution.application.RecordedRunTransition
import org.ergon.controlplane.resolution.application.RunSupervisorAssignment
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.controlplane.resolution.application.StoredRunSupervisorAssignment
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationReceiptSnapshot
import org.ergon.resolution.domain.CapabilityInvocationTiming
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
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
class BrowserAssignedRunConsoleApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var console: AssignedRunConsoleService

    @Test
    fun `assigned list contains only the service projection and is never cached`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito
            .`when`(console.list(tenantId, actorId, 30, null))
            .thenReturn(
                AssignedRunPage(
                    entries =
                        listOf(
                            AssignedRunOverview(
                                ASSIGNMENT_ID,
                                RUN_ID,
                                CASE_ID,
                                ResolutionRunState.WAITING_FOR_APPROVAL,
                                0,
                                NOW,
                                NOW,
                            ),
                        ),
                    nextCursor = null,
                ),
            )

        mockMvc
            .perform(get(ASSIGNED_PATH, tenantId).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.entries[0].runId").value(RUN_ID.value.toString()))
            .andExpect(jsonPath("$.entries[0].state").value("WAITING_FOR_APPROVAL"))
            .andExpect(jsonPath("$.entries[0].authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.entries[0].assigningMachineSubject").doesNotExist())
    }

    @Test
    fun `assigned detail exposes pinned start and recorded state but no private assignment proof`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito.`when`(console.get(tenantId, RUN_ID.value, actorId)).thenReturn(detail(actorId))

        mockMvc
            .perform(get(DETAIL_PATH, tenantId, RUN_ID.value).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caseId").value(CASE_ID.value.toString()))
            .andExpect(jsonPath("$.caseEvidenceStreamVersion").value(4))
            .andExpect(jsonPath("$.contractKey").value("restore-workspace-access"))
            .andExpect(jsonPath("$.state").value("WAITING_FOR_APPROVAL"))
            .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.assigningMachineSubject").doesNotExist())
            .andExpect(jsonPath("$.leaseOwner").doesNotExist())
            .andExpect(jsonPath("$.spans").doesNotExist())
            .andExpect(jsonPath("$.execution").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `assigned execution exposes exact runtime timing without private receipt coordinates or invented cost`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito
            .`when`(console.get(tenantId, RUN_ID.value, actorId))
            .thenReturn(detail(actorId).copy(execution = execution(measured = true)))

        mockMvc
            .perform(get(DETAIL_PATH, tenantId, RUN_ID.value).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.execution.stepId").value("unlock-account"))
            .andExpect(jsonPath("$.execution.capability").value("identity.account.unlock"))
            .andExpect(jsonPath("$.execution.connector").value("identity-stub"))
            .andExpect(jsonPath("$.execution.outcome").value("FAILED"))
            .andExpect(jsonPath("$.execution.timing.source").value("ergon.dev/measurement/connector-call/v1"))
            .andExpect(jsonPath("$.execution.timing.elapsedNanoseconds").value(Long.MAX_VALUE.toString()))
            .andExpect(jsonPath("$.execution.timing.startedAt").value(NOW.toString()))
            .andExpect(jsonPath("$.execution.cost").doesNotExist())
            .andExpect(jsonPath("$.execution.authorizationConsumptionId").doesNotExist())
            .andExpect(jsonPath("$.execution.authorizationGrantId").doesNotExist())
            .andExpect(jsonPath("$.execution.providerOperationReference").doesNotExist())
    }

    @Test
    fun `historical execution retains outcome with explicitly absent timing`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito
            .`when`(console.get(tenantId, RUN_ID.value, actorId))
            .thenReturn(detail(actorId).copy(execution = execution(measured = false)))

        mockMvc
            .perform(get(DETAIL_PATH, tenantId, RUN_ID.value).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.execution.outcome").value("FAILED"))
            .andExpect(jsonPath("$.execution.timing").value(org.hamcrest.Matchers.nullValue()))
    }

    private fun execution(measured: Boolean): StoredCapabilityInvocationReceipt {
        val consumptionId = CapabilityAuthorizationConsumptionId(UUID.randomUUID())
        return StoredCapabilityInvocationReceipt(
            CapabilityInvocationReceipt.rehydrate(
                CapabilityInvocationReceiptSnapshot(
                    authorizationConsumptionId = consumptionId,
                    authorizationGrantId = CapabilityAuthorizationGrantId(UUID.randomUUID()),
                    runId = RUN_ID,
                    caseId = CASE_ID,
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                    stepId = ResolutionStepId.of("unlock-account"),
                    capability = CapabilityName.of("identity.account.unlock"),
                    connector = ConnectorName.of("identity-stub"),
                    idempotencyKey = consumptionId.value,
                    outcome = CapabilityInvocationOutcome.FAILED,
                    providerOperationReference = ProviderOperationReference.of("private-provider-operation"),
                    consumptionConsumedAt = NOW.minusSeconds(1),
                    completedAt = NOW,
                    timing = if (measured) CapabilityInvocationTiming(NOW, Long.MAX_VALUE) else null,
                ),
            ),
            NOW,
        )
    }

    @Test
    fun `browser history exposes sequence and state facts without private event attribution`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val transition =
            RecordedRunTransition(
                RUN_ID,
                1,
                ResolutionRunEventType.CAPABILITY_FAILED,
                ResolutionRunState.WAITING_FOR_APPROVAL,
                ResolutionRunState.ACTION_FAILED,
                NOW,
                NOW,
            )
        Mockito.`when`(console.get(tenantId, RUN_ID.value, actorId)).thenReturn(
            detail(actorId).copy(
                state = ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ACTION_FAILED, 1, NOW),
                execution = execution(measured = true),
                transitions = listOf(transition),
            ),
        )
        mockMvc
            .perform(get(DETAIL_PATH, tenantId, RUN_ID.value).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.transitions[0].sequence").value(1))
            .andExpect(jsonPath("$.transitions[0].type").value("CAPABILITY_FAILED"))
            .andExpect(jsonPath("$.transitions[0].toState").value("ACTION_FAILED"))
            .andExpect(jsonPath("$.transitions[0].recordedAt").value(NOW.toString()))
            .andExpect(jsonPath("$.transitions[0].eventId").doesNotExist())
            .andExpect(jsonPath("$.transitions[0].actorId").doesNotExist())
            .andExpect(jsonPath("$.transitions[0].authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.transitions[0].authorizationConsumptionId").doesNotExist())
    }

    @Test
    fun `hidden run detail uses one non-disclosing absence`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito.`when`(console.get(tenantId, RUN_ID.value, actorId)).thenThrow(AssignedRunNotFoundException())

        mockMvc
            .perform(get(DETAIL_PATH, tenantId, RUN_ID.value).with(verifiedSession()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:assigned-resolution-run-not-found"))
    }

    @Test
    fun `anonymous browser cannot query assigned runs`() {
        mockMvc.perform(get(ASSIGNED_PATH, UUID.randomUUID())).andExpect(status().isUnauthorized)
    }

    private fun detail(actorId: UUID): AssignedRunDetail {
        val assignment =
            RunSupervisorAssignment(
                id = ASSIGNMENT_ID,
                runId = RUN_ID,
                supervisorActorId = HumanActorId(actorId),
                authorityEvidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                commandId = UUID.randomUUID(),
                assigningMachineSubject = "run-dispatcher",
                assignedAt = NOW,
            )
        val run =
            ResolutionRunStart(
                id = RUN_ID,
                caseId = CASE_ID,
                caseStreamVersion = 4,
                contract =
                    ResolutionContractIdentity(
                        ResolutionContractKey.of("restore-workspace-access"),
                        ResolutionContractRevision.of(1),
                    ),
                policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                stepId = ResolutionStepId.of("unlock-account"),
                capability = CapabilityName.of("identity.account.unlock"),
                effectiveRisk = StepRisk.HIGH,
                requiredApproval = ApprovalRequirement.RESOLVER,
                initialState = ResolutionRunInitialState.WAITING_FOR_APPROVAL,
            )
        return AssignedRunDetail(
            StoredRunSupervisorAssignment(assignment, NOW),
            StoredResolutionRunStart(run, NOW),
            ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.WAITING_FOR_APPROVAL, 0, NOW),
            approvalReview =
                org.ergon.controlplane.resolution.application.RunApprovalReview(
                    org.ergon.controlplane.resolution.application.RunApprovalReviewStatus.NOT_REQUESTED,
                    NOW,
                    false,
                    null,
                ),
            pinnedEvidence =
                org.ergon.controlplane.resolution.application
                    .PinnedRunEvidence(CASE_ID.value, 4, emptyList(), false),
        )
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

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val ASSIGNED_PATH = "/bff/v1/tenants/{tenantId}/resolution-runs/assigned"
        private const val DETAIL_PATH = "/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/console"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "run-supervisor-employee"
        private const val ACTOR_REQUEST = """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
        private val ASSIGNMENT_ID = UUID.randomUUID()
        private val RUN_ID = ResolutionRunId(UUID.randomUUID())
        private val CASE_ID = CaseId(UUID.randomUUID())
        private val NOW = Instant.parse("2026-10-09T04:00:00Z")

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
