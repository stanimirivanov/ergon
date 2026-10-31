package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.contracts.PromotionHarness
import org.ergon.controlplane.contracts.adapter.inbound.document.PromotionDocumentCodec
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.ergon.controlplane.evaluation.adapter.inbound.browser.SimulationBrowserDocuments
import org.ergon.controlplane.evaluation.adapter.inbound.browser.SimulationLabController
import org.ergon.controlplane.evaluation.adapter.inbound.browser.SimulationLabExceptionHandler
import org.ergon.controlplane.evaluation.application.EvaluationObservedTarget
import org.ergon.controlplane.evaluation.application.EvaluationReportRepository
import org.ergon.controlplane.evaluation.application.SimulationLabAuthority
import org.ergon.controlplane.evaluation.application.SimulationLabPromotions
import org.ergon.controlplane.evaluation.application.SimulationLabReports
import org.ergon.controlplane.evaluation.application.SimulationReportDiscovery
import org.ergon.controlplane.evaluation.application.SimulationReportSummary
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.adapter.inbound.security.BrowserSessionAudience
import org.ergon.controlplane.identity.adapter.inbound.security.BrowserSessionSecurityConfiguration
import org.ergon.controlplane.identity.adapter.inbound.security.HumanJwtTrust
import org.ergon.controlplane.identity.application.HumanActorAuthenticationService
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.identity.domain.HumanActor
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.net.URI
import java.util.UUID

class SimulationLabBrowserTest {
    @Test
    fun `session tenant authority and CSRF gates precede commands and reads`() =
        withBrowser { mvc, f ->
            mvc.perform(get(f.path + "/reports")).andExpect(status().isUnauthorized)
            val retired = MockHttpSession().also { it.invalidate() }
            mvc.perform(get(f.path + "/reports").session(retired)).andExpect(status().isUnauthorized)
            mvc
                .perform(get("/bff/v1/tenants/${UUID.randomUUID()}/simulation-lab/reports").with(login()))
                .andExpect(status().isNotFound)
            mvc
                .perform(
                    post(f.path + "/promotion-commands/${UUID.randomUUID()}")
                        .with(login())
                        .content(f.command()),
                ).andExpect(status().isForbidden)
            `when`(f.viewer.canView(f.h.tenant, f.h.actor)).thenReturn(false)
            mvc.perform(get(f.path + "/reports").with(login())).andExpect(status().isNotFound)
            mvc
                .perform(
                    post(f.path + "/promotion-commands/${UUID.randomUUID()}")
                        .with(login())
                        .with(csrf())
                        .content(f.command()),
                ).andExpect(status().isNotFound)
            assertThat(f.h.promotions).isEmpty()
        }

    @Test
    fun `bounded exact report projections omit fixtures observations and identity provenance`() =
        withBrowser { mvc, f ->
            mvc
                .perform(get(f.path + "/reports").with(login()))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                    jsonPath("$.items[0].reportId").value(
                        f.h.base.reportId
                            .toString(),
                    ),
                )
            val body =
                mvc
                    .perform(get(f.path + "/reports/${f.h.base.reportId}").with(login()))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.checks").isArray)
                    .andReturn()
                    .response.contentAsString
            assertThat(body).doesNotContain(
                "PRIVATE_RAW_OBSERVATION",
                "observed",
                "definition",
                "submittedBy",
                "sourceReference",
            )
            mvc
                .perform(
                    get(f.path + "/reports")
                        .param("limit", "51")
                        .with(login()),
                ).andExpect(status().isUnprocessableEntity)
            mvc
                .perform(
                    get(f.path + "/reports/${UUID.randomUUID()}")
                        .with(login()),
                ).andExpect(status().isNotFound)
        }

    @Test
    @Suppress("LongMethod") // One review-confirm-retry journey checks that the original intent stays exact.
    fun `reviewed confirmation refuses stale intent and impersonation and preserves exact retry`() =
        withBrowser { mvc, f ->
            val review =
                f.h.base.f.mapper.writeValueAsString(
                    mapOf(
                        "schemaVersion" to "ergon.simulation-lab/review/v1",
                        "reports" to f.h.intent().reports,
                        "rationale" to "Reviewed evidence",
                    ),
                )
            mvc
                .perform(
                    post(f.path + "/contracts/${f.h.key}/promotion-review")
                        .with(login())
                        .with(csrf())
                        .content(review),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.intent.expectedVersion").value(1))
                .andExpect(jsonPath("$.eligibility.eligible").value(true))
            val id = UUID.randomUUID()
            val path = f.path + "/promotion-commands/$id"
            mvc
                .perform(
                    post(path)
                        .with(login())
                        .with(csrf())
                        .header("X-Ergon-Actor-Id", UUID.randomUUID())
                        .content(f.command()),
                ).andExpect(status().isCreated)
            assertThat(
                f.h.promotions
                    .getValue(f.h.tenant to id)
                    .actorId,
            ).isEqualTo(f.h.actor)
            mvc.perform(post(path).with(login()).with(csrf()).content(f.command())).andExpect(status().isOk)
            f.h.service.bind(
                f.h.tenant,
                f.h.key,
                UUID.randomUUID(),
                f.h.actor,
                RequiredSuiteIntent(
                    1,
                    f.h.suite,
                    "New gate",
                ),
            )
            mvc
                .perform(
                    post(f.path + "/promotion-commands/${UUID.randomUUID()}")
                        .with(login())
                        .with(csrf())
                        .content(f.command()),
                ).andExpect(status().isConflict)
            val forged = f.command().dropLast(1) + ",\"actorId\":\"${UUID.randomUUID()}\"}"
            mvc
                .perform(
                    post(path)
                        .with(login())
                        .with(csrf())
                        .content(forged),
                ).andExpect(status().isUnprocessableEntity)
            f.h.active = false
            mvc
                .perform(
                    post(path)
                        .with(login())
                        .with(csrf())
                        .content(f.command()),
                ).andExpect(status().isNotFound)
        }

    @Test
    fun `page lookahead returns a usable last-included cursor and excludes the extra row`() {
        val f = SimulationLabBrowserFixture()
        val rows =
            (1..3).map {
                SimulationReportSummary(
                    UUID.randomUUID(),
                    f.h.base.fixtureRef,
                    f.secret.digest,
                    true,
                    f.stored.recordedAt,
                )
            }
        val reads =
            SimulationLabReports(
                f.viewer,
                SimulationReportDiscovery { _, limit, _ ->
                    assertThat(limit).isEqualTo(3)
                    rows
                },
                f.reports,
                f.h.base.f.codec,
            )
        val page = reads.list(f.h.tenant, f.h.actor, 2, null)
        assertThat(page.items).isEqualTo(rows.take(2))
        assertThat(page.nextAfter).isEqualTo(rows[1].reportId)
    }

    private fun login() =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("lab-user")
        }

    private fun withBrowser(block: (MockMvc, SimulationLabBrowserFixture) -> Unit) {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "lab-test",
                    mapOf("ergon.security.browser-session.enabled" to "true"),
                ),
            )
            context.register(SimulationLabBrowserTestConfiguration::class.java)
            context.refresh()
            val mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
            block(mvc, context.getBean(SimulationLabBrowserFixture::class.java))
        }
    }
}

internal class SimulationLabBrowserFixture {
    val h = PromotionHarness().also { it.bind() }
    val path = "/bff/v1/tenants/${h.tenant}/simulation-lab"
    val viewer =
        mock(SimulationLabAuthority::class.java).also {
            `when`(
                it.canView(
                    h.tenant,
                    h.actor,
                ),
            ).thenReturn(true)
        }
    val codec = PromotionDocumentCodec(h.base.f.pinned, h.base.f.mapper)

    fun command() = codec.encode(h.intent())

    val stored = checkNotNull(h.base.reports.find(h.tenant, h.base.reportId))
    val secret =
        h.base.f.codec.encode(
            h.base.preparedReport.content.copy(
                observed =
                    listOf(
                        EvaluationObservedTarget(
                            "scenario",
                            mapOf("value" to "PRIVATE_RAW_OBSERVATION"),
                        ),
                    ),
            ),
        )
    val reports =
        mock(EvaluationReportRepository::class.java).also {
            `when`(
                it.find(
                    h.tenant,
                    h.base.reportId,
                ),
            ).thenReturn(
                stored.copy(
                    definition = secret.definition,
                    reportDigest = secret.digest,
                ),
            )
        }
    val reads =
        SimulationLabReports(
            viewer,
            SimulationReportDiscovery { _, _, _ ->
                listOf(
                    SimulationReportSummary(
                        h.base.reportId,
                        h.base.fixtureRef,
                        secret.digest,
                        stored.passed,
                        stored.recordedAt,
                    ),
                )
            },
            reports,
            h.base.f.codec,
        )
    val promotions =
        SimulationLabPromotions(
            viewer,
            h.transactions,
            h.service,
            h.base.suites,
            h.base.codec,
            h.base.gate,
        )
}

@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableWebMvc
@Import(BrowserSessionSecurityConfiguration::class, SimulationLabExceptionHandler::class)
internal class SimulationLabBrowserTestConfiguration {
    @Bean fun fixture() = SimulationLabBrowserFixture()

    @Bean fun audience() = BrowserSessionAudience.WORKBENCH

    @Bean fun trust() = HumanJwtTrust(URI("https://identity.example.test"), "workforce-sso")

    @Bean fun registrations() = BrowserSessionTestClientConfiguration().browserClientRegistrationRepository("workbench")

    @Bean fun mapper(f: SimulationLabBrowserFixture) = f.h.base.f.mapper

    @Bean fun controller(
        f: SimulationLabBrowserFixture,
        trust: HumanJwtTrust,
    ): SimulationLabController {
        val identities = mock(HumanAuthorityRepository::class.java)
        `when`(identities.find(TenantId(f.h.tenant), "workforce-sso", "lab-user")).thenReturn(
            StoredHumanActor(
                HumanActor.create(
                    HumanActorId(f.h.actor),
                    "workforce-sso",
                    "lab-user",
                ),
                f.h.at,
                f.h.at,
            ),
        )
        return SimulationLabController(
            AuthenticatedHumanActorResolver(HumanActorAuthenticationService(identities), trust),
            f.reads,
            f.promotions,
            SimulationBrowserDocuments(f.h.base.f.pinned),
            f.codec,
        )
    }
}
