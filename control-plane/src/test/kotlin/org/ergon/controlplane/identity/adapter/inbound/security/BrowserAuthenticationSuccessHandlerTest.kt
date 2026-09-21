package org.ergon.controlplane.identity.adapter.inbound.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import java.util.UUID

class BrowserAuthenticationSuccessHandlerTest {
    private val handler = BrowserAuthenticationSuccessHandler()

    @Test
    fun `redirects to the server-held workbench path and consumes it`() {
        val returnTo = "/tenants/${UUID.randomUUID()}"
        val request = MockHttpServletRequest()
        val session = requireNotNull(request.session)
        session.setAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE, returnTo)
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(request, response, TestingAuthenticationToken("user", null))

        assertThat(response.redirectedUrl).isEqualTo(returnTo)
        assertThat(session.getAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE)).isNull()
    }

    @Test
    fun `redirects to the application root when no workbench path was requested`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(request, response, TestingAuthenticationToken("user", null))

        assertThat(response.redirectedUrl).isEqualTo("/")
    }
}
