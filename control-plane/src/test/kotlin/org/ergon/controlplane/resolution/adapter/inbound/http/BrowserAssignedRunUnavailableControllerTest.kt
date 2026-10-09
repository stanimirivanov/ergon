package org.ergon.controlplane.resolution.adapter.inbound.http

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
    }
}
