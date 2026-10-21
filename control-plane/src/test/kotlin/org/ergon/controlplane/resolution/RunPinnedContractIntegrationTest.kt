package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CancelSupervisedRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.RunCancellationService
import org.ergon.controlplane.resolution.application.RunPinnedContractService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.execution.enabled=false",
        "ergon.run-supervision.handover.enabled=true",
        "ergon.run-supervision.cancellation.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunPinnedContractIntegrationTest.FixedTime::class)
// Real authority, immutable repositories and lifecycle transitions prove the disclosure boundary.
@Suppress("LongParameterList")
class RunPinnedContractIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val service: RunPinnedContractService,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val cancellations: RunCancellationService,
) {
    @MockitoSpyBean
    private lateinit var contracts: ResolutionContractRevisionRepository

    @MockitoSpyBean
    private lateinit var runs: ResolutionRunRepository

    @Test
    fun `normalized fifty-step declarations preserve order and safeguards without execution or private attribution`() {
        val fixture = seed(contract(steps = 50))
        val response =
            read(fixture)
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.runId").value(fixture.run.value.toString()))
                .andExpect(jsonPath("$.supervisionRevision").value(0))
                .andExpect(jsonPath("$.observedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.pinnedStepId").value("unlock-account"))
                .andExpect(jsonPath("$.contractRecordedAt").isString)
                .andExpect(jsonPath("$.contract.length()").value(7))
                .andExpect(jsonPath("$.contract.schema").value("ergon.dev/resolution-contract/v1alpha1"))
                .andExpect(jsonPath("$.contract.key").value("restore-workspace-access"))
                .andExpect(jsonPath("$.contract.revision").value(1))
                .andExpect(jsonPath("$.contract.applicability.equals").value("LOCKED"))
                .andExpect(jsonPath("$.contract.requiredEvidence[0]").value("account.access.state"))
                .andExpect(jsonPath("$.contract.steps.length()").value(50))
                .andExpect(jsonPath("$.contract.steps[0].length()").value(4))
                .andExpect(jsonPath("$.contract.steps[0].risk").value("LOW"))
                .andExpect(jsonPath("$.contract.steps[0].approval").value("NONE"))
                .andExpect(jsonPath("$.contract.steps[1].id").value("step-49"))
                .andExpect(jsonPath("$.contract.steps[49].id").value("step-1"))
                .andExpect(jsonPath("$.contract.outcomeProof.equals").value("ACTIVE"))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain(
            fixture.outgoing.value.toString(),
            fixture.outgoingEvidence.value.toString(),
            "authorityEvidenceId",
            "machine",
            "provider",
            "definition",
            "yaml",
            "stateVersion",
            "policyRevision",
        )
        assertThat(runs.find(fixture.tenant, fixture.run)?.run?.effectiveRisk).isEqualTo(StepRisk.HIGH)
        assertNoReadEffects(fixture)
    }

    @Test
    fun `read uses the exact pinned revision and tenant never a newer or foreign definition`() {
        val fixture = seed()
        contracts.publish(fixture.tenant, contract(revision = 2, expected = "UNPINNED-VALUE"))
        contracts.publish(TenantId(UUID.randomUUID()), contract(expected = "OTHER-TENANT-VALUE"))
        val response =
            read(fixture)
                .andExpect(jsonPath("$.contract.revision").value(1))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain("UNPINNED-VALUE", "OTHER-TENANT-VALUE")
        assertNoReadEffects(fixture)
    }

    @Test
    fun `handover before cancellation retains terminal access only for the current supervisor`() {
        val fixture = seed()
        read(fixture)
        handovers.handover(
            HandoverRunSupervisorCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            ),
        )
        cancellations.cancel(
            CancelSupervisedRunCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(1),
                0,
            ),
        )
        mvc
            .perform(get(path(fixture)).with(session(fixture.outgoing)))
            .andExpect(status().isNotFound)
            .andExpect(header().string("Cache-Control", "no-store"))
        read(fixture, fixture.incoming).andExpect(jsonPath("$.supervisionRevision").value(1))
        val later =
            RunPinnedContractService(
                assignments,
                authorities,
                runs,
                contracts,
                transactions,
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC),
            )
        assertThatThrownBy { later.get(fixture.tenant.value, fixture.run.value, fixture.incoming.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    @Test
    fun `missing foreign unassigned and expired access never reaches private run or contract lookup`() {
        val fixture = seed()
        Mockito.clearInvocations(runs, contracts)
        for ((tenant, run, actor) in listOf(
            Triple(fixture.tenant.value, fixture.run.value, fixture.incoming.value),
            Triple(fixture.tenant.value, fixture.run.value, fixture.expired.value),
            Triple(fixture.tenant.value, UUID.randomUUID(), fixture.outgoing.value),
            Triple(UUID.randomUUID(), fixture.run.value, fixture.outgoing.value),
        )) {
            assertThatThrownBy { service.get(tenant, run, actor) }
                .isInstanceOf(AssignedRunNotFoundException::class.java)
        }
        Mockito.verifyNoInteractions(runs, contracts)
        for (actor in listOf(fixture.incoming, fixture.expired)) {
            mvc
                .perform(get(path(fixture)).with(session(actor)))
                .andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:assigned-resolution-run-not-found"))
                .andExpect(jsonPath("$.contract").doesNotExist())
        }
        Mockito.verifyNoInteractions(runs, contracts)
    }

    @Test
    fun `identity and malformed coordinates use no-store safe failures`() {
        val fixture = seed()
        mvc.perform(get(path(fixture))).andExpect(status().isUnauthorized)
        for (coordinate in listOf(fixture.tenant.value, fixture.run.value)) {
            val invalidPath = path(fixture).replace(coordinate.toString(), "not-a-uuid")
            mvc
                .perform(get(invalidPath).with(session(fixture.outgoing)))
                .andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-run-pinned-contract"))
        }
        mvc
            .perform(get(path(fixture)).with(oidcLogin().idToken { it.issuer("https://untrusted.example.test") }))
            .andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:untrusted-human-identity-issuer"))
        mvc
            .perform(
                get(path(fixture).replace(fixture.tenant.value.toString(), UUID.randomUUID().toString()))
                    .with(session(fixture.outgoing)),
            ).andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
    }

    @Test
    fun `storage and pinned-capability corruption return cause-free unavailability not a contract`() {
        val fixture = seed()
        val start = checkNotNull(runs.find(fixture.tenant, fixture.run))
        Mockito
            .doThrow(DataAccessResourceFailureException("PRIVATE_CONTRACT_DIAGNOSTIC"))
            .`when`(contracts)
            .find(fixture.tenant, start.run.contract.key, start.run.contract.revision)
        assertUnavailable(fixture)
        Mockito.reset(contracts)
        Mockito
            .doReturn(
                StoredResolutionRunStart(
                    start.run.copy(stepId = ResolutionStepId.of("not-declared")),
                    start.recordedAt,
                ),
            ).`when`(runs)
            .find(fixture.tenant, fixture.run)
        assertUnavailable(fixture)
    }

    private fun assertUnavailable(fixture: RunSupervisionDatabaseFixture) {
        val response =
            mvc
                .perform(get(path(fixture)).with(session(fixture.outgoing)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-pinned-contract-unavailable"))
                .andExpect(jsonPath("$.contract").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain(
            "PRIVATE_CONTRACT_DIAGNOSTIC",
            "stackTrace",
            "DataAccess",
            "jdbc",
            "not-declared",
        )
    }

    private fun seed(definition: ResolutionContract? = null): RunSupervisionDatabaseFixture {
        val tenant = TenantId(UUID.randomUUID())
        if (definition != null) contracts.publish(tenant, definition)
        return transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, NOW, tenant = tenant) }
    }

    private fun read(
        fixture: RunSupervisionDatabaseFixture,
        actor: HumanActorId = fixture.outgoing,
    ) = mvc
        .perform(get(path(fixture)).with(session(actor)))
        .andExpect(status().isOk)
        .andExpect(header().string("Cache-Control", "no-store"))

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken { it.issuer("https://identity.example.test").subject("resolver-${actor.value}") }

    private fun path(fixture: RunSupervisionDatabaseFixture): String =
        "/bff/v1/tenants/${fixture.tenant.value}/resolution-runs/${fixture.run.value}/pinned-contract"

    private fun assertNoReadEffects(fixture: RunSupervisionDatabaseFixture) {
        for (table in listOf(
            "resolution_run_control_events",
            "capability_authorization_grants",
            "capability_authorization_consumptions",
            "capability_invocation_receipts",
        )) {
            val count =
                jdbc
                    .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
                    .param("tenant", fixture.tenant.value)
                    .query(Int::class.java)
                    .single()
            assertThat(count).describedAs(table).isZero()
        }
    }

    private fun contract(
        revision: Int = 1,
        steps: Int = 1,
        expected: String = "LOCKED",
    ): ResolutionContract {
        val fact = ContractFactType.of("account.access.state")
        return ResolutionContract.define(
            ResolutionContractIdentity(
                ResolutionContractKey.of("restore-workspace-access"),
                ResolutionContractRevision.of(revision),
            ),
            FactCondition(fact, ContractFactValue.of(expected)),
            listOf(fact),
            List(steps) { index ->
                ResolutionStep(
                    ResolutionStepId.of(if (index == 0) "unlock-account" else "step-${steps - index}"),
                    CapabilityName.of("identity.account.unlock"),
                    StepRisk.LOW,
                    ApprovalRequirement.NONE,
                )
            },
            FactCondition(fact, ContractFactValue.of("ACTIVE")),
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun pinnedContractClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-20T08:00:00Z")

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
