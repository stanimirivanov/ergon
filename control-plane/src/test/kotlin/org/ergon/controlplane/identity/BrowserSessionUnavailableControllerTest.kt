package org.ergon.controlplane.identity

import org.ergon.controlplane.identity.adapter.inbound.http.BrowserSessionUnavailableController
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class BrowserSessionUnavailableControllerTest {
    private val mockMvc = MockMvcBuilders.standaloneSetup(BrowserSessionUnavailableController()).build()

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
    }
}
