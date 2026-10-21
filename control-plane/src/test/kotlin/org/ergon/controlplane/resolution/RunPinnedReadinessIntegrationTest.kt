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
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CancelSupervisedRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.PinnedReadinessEvidenceReader
import org.ergon.controlplane.resolution.application.PinnedRunEvidenceReader
import org.ergon.controlplane.resolution.application.RunCancellationService
import org.ergon.controlplane.resolution.application.RunPinnedReadinessService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
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
        "ergon.run-supervision.handover.enabled=true",
        "ergon.run-supervision.cancellation.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunPinnedReadinessIntegrationTest.FixedTime::class)
// Actual case construction, private reads, lifecycle and HTTP form the verification boundary.
@Suppress("LongParameterList")
class RunPinnedReadinessIntegrationTest(
    @Autowired private val cases: CaseCommandService,
    @Autowired private val timelines: CaseTimelineRepository,
    @Autowired private val history: PinnedRunEvidenceReader,
    @Autowired private val service: RunPinnedReadinessService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val cancellations: RunCancellationService,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mvc: MockMvc,
) {
    @MockitoSpyBean
    private lateinit var evidence: PinnedReadinessEvidenceReader

    @Test
    fun `latest pinned binding beyond truncated history wins without disclosing later or unbound sources`() {
        val tenant = TenantId(UUID.randomUUID())
        var snapshot = cases.open(OpenCaseCommand(tenant.value, "Restore", "UNRELATED_REQUESTER"))
        repeat(100) { snapshot = bind(tenant, snapshot, "OLD_$it", AccountAccessState.ACTIVE) }
        val pinned = bind(tenant, snapshot, "SELECTED <b>INERT</b>", AccountAccessState.LOCKED)
        val fixture = seed(tenant, pinned)
        bind(tenant, pinned, "LATER_SECRET", AccountAccessState.ACTIVE)
        val truncated = checkNotNull(history.find(tenant, CaseId(pinned.caseId), pinned.streamVersion))
        assertThat(truncated.truncated).isTrue()
        assertThat(truncated.facts).allMatch { it.value == AccountAccessState.ACTIVE }
        val body =
            read(fixture)
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$.evaluator").value("ergon.dev/evaluation/pinned-readiness/v1"))
                .andExpect(jsonPath("$.caseStreamVersion").value(pinned.streamVersion))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.missingEvidence.length()").value(0))
                .andExpect(jsonPath("$.applicability.actualValue").value("LOCKED"))
                .andExpect(jsonPath("$.selectedEvidence.length()").value(1))
                .andExpect(jsonPath("$.selectedEvidence[0].streamVersion").value(pinned.streamVersion))
                .andExpect(jsonPath("$.selectedEvidence[0].source.content").value("SELECTED <b>INERT</b>"))
                .andReturn()
                .response.contentAsString
        assertThat(body).doesNotContain(
            "UNRELATED_REQUESTER",
            "OLD_",
            "LATER_SECRET",
            fixture.outgoing.value.toString(),
            "authorityEvidenceId",
            "providerOperationReference",
        )
        for (table in listOf(
            "capability_authorization_grants",
            "capability_invocation_receipts",
            "resolution_run_control_events",
        )) {
            assertThat(
                jdbc
                    .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
                    .param("tenant", tenant.value)
                    .query(Int::class.java)
                    .single(),
            ).describedAs(table).isZero()
        }
    }

    @Test
    fun `missing and mismatching pinned evidence remain distinct from current case state`() {
        val tenant = TenantId(UUID.randomUUID())
        val opened = cases.open(OpenCaseCommand(tenant.value, "Restore", "Private request"))
        val observed = observe(tenant, opened, "NOT_BOUND_AT_PIN")
        val missing = seed(tenant, observed)
        val sourceId =
            checkNotNull(timelines.find(tenant, CaseId(opened.caseId)))
                .entries
                .last()
                .observation.id
        val active =
            cases.bindAccountAccessState(
                BindAccountAccessStateCommand(
                    tenantId = tenant.value,
                    caseId = opened.caseId,
                    expectedVersion = observed.streamVersion,
                    observationId = sourceId,
                    state = AccountAccessState.ACTIVE,
                ),
            )
        read(missing)
            .andExpect(jsonPath("$.status").value("WAITING_FOR_EVIDENCE"))
            .andExpect(jsonPath("$.missingEvidence[0]").value("account.access.state"))
            .andExpect(jsonPath("$.applicability").isEmpty)
            .andExpect(jsonPath("$.selectedEvidence.length()").value(0))
        val mismatchCase = cases.open(OpenCaseCommand(tenant.value, "Restore separately", "Other request"))
        val mismatch = seed(tenant, bind(tenant, mismatchCase, "Mismatch source", AccountAccessState.ACTIVE))
        read(mismatch)
            .andExpect(jsonPath("$.status").value("NOT_APPLICABLE"))
            .andExpect(jsonPath("$.applicability.expectedValue").value("LOCKED"))
            .andExpect(jsonPath("$.applicability.actualValue").value("ACTIVE"))
        assertThat(
            evidence.findLatest(
                TenantId(UUID.randomUUID()),
                CaseId(opened.caseId),
                active.streamVersion,
            ),
        ).isNull()
        assertThat(evidence.findLatest(tenant, CaseId(UUID.randomUUID()), 1)).isNull()
        assertThat(evidence.findLatest(tenant, CaseId(opened.caseId), active.streamVersion + 1)).isNull()
    }

    @Test
    fun `incoming supervisor can inspect terminal work while former supervisor cannot`() {
        val fixture = seed()
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
        read(fixture, fixture.incoming)
            .andExpect(jsonPath("$.supervisionRevision").value(1))
            .andExpect(jsonPath("$.status").value("WAITING_FOR_EVIDENCE"))
    }

    @Test
    fun `unassigned expired foreign and missing access never invokes the evidence reader`() {
        val fixture = seed()
        Mockito.clearInvocations(evidence)
        for (actor in listOf(fixture.incoming, fixture.other, fixture.expired)) {
            assertThatThrownBy { service.get(fixture.tenant.value, fixture.run.value, actor.value) }
                .isInstanceOf(AssignedRunNotFoundException::class.java)
            mvc
                .perform(get(path(fixture)).with(session(actor)))
                .andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:assigned-resolution-run-not-found"))
        }
        assertThatThrownBy { service.get(UUID.randomUUID(), fixture.run.value, fixture.outgoing.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy { service.get(fixture.tenant.value, UUID.randomUUID(), fixture.outgoing.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(evidence)
    }

    @Test
    fun `malformed coordinates and identity failure remain safe and no store`() {
        val fixture = seed()
        mvc.perform(get(path(fixture))).andExpect(status().isUnauthorized)
        for (coordinate in listOf(fixture.tenant.value, fixture.run.value)) {
            mvc
                .perform(get(path(fixture).replace(coordinate.toString(), "invalid")).with(session(fixture.outgoing)))
                .andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-run-pinned-readiness"))
        }
        mvc
            .perform(get(path(fixture)).with(oidcLogin().idToken { it.issuer("https://untrusted.example.test") }))
            .andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc
            .perform(
                get(path(fixture).replace(fixture.tenant.value.toString(), UUID.randomUUID().toString()))
                    .with(session(fixture.outgoing)),
            ).andExpect(status().isForbidden)
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun `unrecoverable immutable prefix is unavailable rather than missing evidence`() {
        val tenant = TenantId(UUID.randomUUID())
        val opened = cases.open(OpenCaseCommand(tenant.value, "Restore", "Request"))
        // Fault fixture: no such prefix exists. Production constraints and reader checks remain unchanged.
        val fixture = seed(tenant, opened.copy(streamVersion = opened.streamVersion + 1))
        mvc
            .perform(get(path(fixture)).with(session(fixture.outgoing)))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-pinned-readiness-unavailable"))
            .andExpect(jsonPath("$.selectedEvidence").doesNotExist())
    }

    @Test
    fun `storage and unavailable prefix are cause free failures never missing evidence`() {
        val fixture = seed()
        val caseId = service.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).pin.caseId
        val missing = CaseId(UUID.randomUUID())
        Mockito
            .doThrow(DataAccessResourceFailureException("PRIVATE_SQL"))
            .`when`(evidence)
            .findLatest(fixture.tenant, caseId, 1)
        val body =
            mvc
                .perform(get(path(fixture)).with(session(fixture.outgoing)))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-pinned-readiness-unavailable"))
                .andExpect(jsonPath("$.selectedEvidence").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertThat(body).doesNotContain("PRIVATE_SQL", "jdbc", "stackTrace")
        assertThat(evidence.findLatest(fixture.tenant, missing, 1)).isNull()
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
                "account",
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
        val source =
            checkNotNull(timelines.find(tenant, CaseId(case.caseId)))
                .entries
                .last()
                .observation.id
        return cases.bindAccountAccessState(
            BindAccountAccessStateCommand(
                tenant.value,
                case.caseId,
                observed.streamVersion,
                source,
                state,
            ),
        )
    }

    private fun seed(
        tenant: TenantId = TenantId(UUID.randomUUID()),
        case: CaseWriteResult? = null,
    ): RunSupervisionDatabaseFixture =
        transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, NOW, caseSnapshot = case, tenant = tenant) }

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
        "/bff/v1/tenants/${fixture.tenant.value}/resolution-runs/${fixture.run.value}/pinned-readiness"

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun readinessClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-21T08:00:00Z")

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
