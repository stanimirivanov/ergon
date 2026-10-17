package org.ergon.controlplane.resolution.adapter.inbound.http

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/** Keeps disabled-browser behavior explicit when the assigned-run routes evolve. */
class BrowserAssignedRunUnavailableControllerTest {
    private val mockMvc = MockMvcBuilders.standaloneSetup(BrowserAssignedRunUnavailableController()).build()

    @Test
    fun `assigned run reads fail closed when browser sessions are disabled`() {
        val tenantId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val problemType = "urn:ergon:problem:browser-authentication-unavailable"

        mockMvc
            .perform(get("/bff/v1/tenants/{tenantId}/resolution-runs/assigned", tenantId))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(problemType))

        mockMvc
            .perform(get("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/console", tenantId, runId))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value(problemType))

        mockMvc
            .perform(post("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/proof-finalizations", tenantId, runId))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value(problemType))

        mockMvc
            .perform(get("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/execution-review", tenantId, runId))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
        mockMvc
            .perform(get("/bff/v1/tenants/$tenantId/resolution-runs/$runId/retry-review"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
        val suffixes =
            listOf(
                "retry-commands",
                "escalation-commands",
                "execution-commands",
                "execution-commands/${UUID.randomUUID()}/attempts",
            )
        for (suffix in suffixes) {
            mockMvc
                .perform(post("/bff/v1/tenants/$tenantId/resolution-runs/$runId/$suffix"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
        }
        mockMvc
            .perform(get("/bff/v1/tenants/$tenantId/resolution-runs/$runId/escalation-review"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value(problemType))
    }
}
