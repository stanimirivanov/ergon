package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.BindAccountAccessStateCommand
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.CaseWriteResult
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.PinnedRunEvidenceReader
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
        "ergon.run-supervision.handover.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Import(BrowserSessionTestClientConfiguration::class, PinnedRunEvidenceIntegrationTest.FixedTime::class)
@Testcontainers(disabledWithoutDocker = true)
@Suppress("LongParameterList") // Real case construction, security and persistence boundaries are the evidence.
class PinnedRunEvidenceIntegrationTest(
    @Autowired private val cases: CaseCommandService,
    @Autowired private val timelines: CaseTimelineRepository,
    @Autowired private val reader: PinnedRunEvidenceReader,
    @Autowired private val console: AssignedRunConsoleService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `browser discloses only pinned bindings and exact sources and withdraws access after handover`() {
        val tenant = TenantId(UUID.randomUUID())
        var case = cases.open(OpenCaseCommand(tenant.value, "Restore access", "UNRELATED_REQUESTER_TEXT"))
        case = observe(tenant, case, "UNBOUND_PRIVATE_TEXT")
        val pinned = bind(tenant, case, "PINNED_CONNECTOR_TEXT", AccountAccessState.LOCKED)
        bind(tenant, pinned, "LATER_PRIVATE_TEXT", AccountAccessState.ACTIVE)
        val fixture = seed(tenant, pinned)
        val path = "/bff/v1/tenants/${tenant.value}/resolution-runs/${fixture.run.value}/console"

        val response =
            mvc
                .perform(get(path).with(session(fixture.outgoing.value)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pinnedEvidence.caseId").value(pinned.caseId.toString()))
                .andExpect(jsonPath("$.pinnedEvidence.caseStreamVersion").value(pinned.streamVersion))
                .andExpect(jsonPath("$.pinnedEvidence.facts.length()").value(1))
                .andExpect(jsonPath("$.pinnedEvidence.facts[0].value").value("LOCKED"))
                .andExpect(jsonPath("$.pinnedEvidence.facts[0].source.content").value("PINNED_CONNECTOR_TEXT"))
                .andReturn()
                .response.contentAsString
        assertThat(response).doesNotContain("UNRELATED_REQUESTER_TEXT", "UNBOUND_PRIVATE_TEXT", "LATER_PRIVATE_TEXT")
        assertThat(
            console
                .get(tenant.value, fixture.run.value, fixture.outgoing.value)
                .pinnedEvidence.facts
                .single()
                .source.streamVersion,
        ).isEqualTo(pinned.streamVersion - 1)
        mvc.perform(get(path).with(session(fixture.other.value))).andExpect(status().isNotFound())
        mvc.perform(get(path).with(session(fixture.expired.value))).andExpect(status().isNotFound())
        mvc.perform(get(path)).andExpect(status().isUnauthorized())
        val foreignPath = path.replace(tenant.value.toString(), UUID.randomUUID().toString())
        mvc
            .perform(get(foreignPath).with(session(fixture.outgoing.value)))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))

        handovers.handover(
            HandoverRunSupervisorCommand(
                tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            ),
        )
        mvc.perform(get(path).with(session(fixture.outgoing.value))).andExpect(status().isNotFound())
        mvc.perform(get(path).with(session(fixture.incoming.value))).andExpect(status().isOk())
    }

    @Test
    fun `a source inside the boundary is not disclosed when its binding is later`() {
        val tenant = TenantId(UUID.randomUUID())
        val case = cases.open(OpenCaseCommand(tenant.value, "Restore access", "Request"))
        val observed = observe(tenant, case, "NOT_YET_BOUND_AT_PIN")
        val sourceId =
            checkNotNull(timelines.find(tenant, CaseId(case.caseId)))
                .entries
                .last()
                .observation.id
        cases.bindAccountAccessState(
            BindAccountAccessStateCommand(
                tenant.value,
                case.caseId,
                observed.streamVersion,
                sourceId,
                AccountAccessState.ACTIVE,
            ),
        )
        val snapshot = reader.find(tenant, CaseId(case.caseId), observed.streamVersion)
        assertThat(snapshot?.facts).isEmpty()
        assertThat(snapshot?.truncated).isFalse()
        assertThat(reader.find(TenantId(UUID.randomUUID()), CaseId(case.caseId), observed.streamVersion)).isNull()
        assertThat(reader.find(tenant, CaseId(UUID.randomUUID()), 1)).isNull()
        assertThat(reader.find(tenant, CaseId(case.caseId), 999)).isNull()
        assertThatThrownBy { reader.find(tenant, CaseId(case.caseId), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `bounded history reports truncation without selecting a mutable latest value`() {
        val tenant = TenantId(UUID.randomUUID())
        var case = cases.open(OpenCaseCommand(tenant.value, "Restore access", "Request"))
        repeat(101) { case = bind(tenant, case, "Observation $it", AccountAccessState.LOCKED) }
        val snapshot = checkNotNull(reader.find(tenant, CaseId(case.caseId), case.streamVersion))
        assertThat(snapshot.facts).hasSize(100)
        assertThat(snapshot.truncated).isTrue()
        assertThat(snapshot.facts.first().streamVersion).isEqualTo(3)
        assertThat(snapshot.facts.last().streamVersion).isEqualTo(201)
        assertThat(snapshot.facts.map { it.source.content }).doesNotContain("Observation 100")
    }

    private fun observe(
        tenant: TenantId,
        case: CaseWriteResult,
        content: String,
    ): CaseWriteResult =
        cases.recordConnectorObservation(
            RecordConnectorObservationCommand(
                tenant.value,
                case.caseId,
                case.streamVersion,
                "synthetic-identity",
                "account-synthetic",
                content,
            ),
        )

    private fun bind(
        tenant: TenantId,
        case: CaseWriteResult,
        content: String,
        state: AccountAccessState,
    ): CaseWriteResult {
        val observed = observe(tenant, case, content)
        val sourceId =
            checkNotNull(timelines.find(tenant, CaseId(case.caseId)))
                .entries
                .last()
                .observation.id
        return cases.bindAccountAccessState(
            BindAccountAccessStateCommand(tenant.value, case.caseId, observed.streamVersion, sourceId, state),
        )
    }

    private fun seed(
        tenant: TenantId,
        case: CaseWriteResult,
    ): RunSupervisionDatabaseFixture =
        transactions.required {
            RunSupervisionDatabaseFixture.seed(jdbc, NOW, caseSnapshot = case, tenant = tenant)
        }

    private fun session(actor: UUID) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test").subject("resolver-$actor")
        }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun evidenceTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-15T08:00:00Z")

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
