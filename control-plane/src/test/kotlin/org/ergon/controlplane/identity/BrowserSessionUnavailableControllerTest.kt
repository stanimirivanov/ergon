package org.ergon.controlplane.identity

import org.ergon.controlplane.identity.adapter.inbound.http.BrowserSessionUnavailableController
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class BrowserSessionUnavailableControllerTest {
    @Test
    fun `information requests fail closed without browser sessions`() {
        val path = "/bff/v1/tenants/${UUID.randomUUID()}/human-follow-ups/${UUID.randomUUID()}/information-requests"
        mockMvc
            .perform(post(path))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-unavailable"))
        val requesterCase = "/bff/v1/tenants/${UUID.randomUUID()}/requester-cases/${UUID.randomUUID()}"
        val requesterPath = "$requesterCase/information-requests"
        for (readPath in listOf(path, requesterPath)) {
            mockMvc.perform(get(readPath)).andExpect(status().isServiceUnavailable)
        }
    }

    @Test
    fun `requester progress is unavailable without browser sessions`() {
        val path = "/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/progress"
        mockMvc
            .perform(get(path, UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isServiceUnavailable)
    }

    private val mockMvc = MockMvcBuilders.standaloneSetup(BrowserSessionUnavailableController()).build()

    @Test
    fun `priority fails closed when browser sessions are unavailable`() {
        val path = "/bff/v1/tenants/${UUID.randomUUID()}/human-follow-ups/${UUID.randomUUID()}/priority-assignments"
        mockMvc
            .perform(post(path))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-unavailable"))
    }

    @Test
    fun `completion fails closed when browser sessions are unavailable`() {
        mockMvc
            .perform(post("/bff/v1/tenants/${UUID.randomUUID()}/human-follow-ups/${UUID.randomUUID()}/completions"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-unavailable"))
    }

    @Test
    fun `handover fails closed when browser sessions are unavailable`() {
        mockMvc
            .perform(post("/bff/v1/tenants/${UUID.randomUUID()}/human-follow-ups/${UUID.randomUUID()}/handovers"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-unavailable"))
    }

    @Test
    fun `notes fail closed when browser sessions are unavailable`() {
        val path = "/bff/v1/tenants/${UUID.randomUUID()}/human-follow-ups/${UUID.randomUUID()}/notes"
        listOf(get(path), post(path)).forEach { request ->
            mockMvc
                .perform(request)
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-unavailable"))
        }
    }

    @Test
    fun `returns a stable unavailable problem for defined browser routes`() {
        val expectedType = "urn:ergon:problem:browser-authentication-unavailable"

        mockMvc
            .perform(get("/bff/login"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(get("/bff/v1/tenants/{tenantId}/session", UUID.randomUUID()))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(get("/bff/v1/tenants/{tenantId}/human-follow-ups", UUID.randomUUID()))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(get("/bff/v1/tenants/{tenantId}/human-follow-ups/owned", UUID.randomUUID()))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(
                get(
                    "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(get("/bff/v1/csrf"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(
                post(
                    "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))

        mockMvc
            .perform(
                post(
                    "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claim-commands",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(expectedType))
    }

    @Test
    fun `release fails closed when browser sessions are unavailable`() {
        mockMvc
            .perform(
                post(
                    "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/{claimId}/release",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
            ).andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-unavailable"))
    }
}
