package org.ergon.evaluation.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EvaluationViewerAuthorityTest {
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val authority =
        EvaluationViewerAuthority(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "trusted-idp",
            "grant-1",
            at,
            at.plusSeconds(60),
        )

    @Test
    fun `viewer entitlement is current only within its half open interval`() {
        assertThat(authority.currentAt(at.minusNanos(1))).isFalse()
        assertThat(authority.currentAt(at)).isTrue()
        assertThat(authority.currentAt(authority.expiresAt.minusNanos(1))).isTrue()
        assertThat(authority.currentAt(authority.expiresAt)).isFalse()
    }

    @Test
    fun `invalid provenance and imprecise or overlong validity cannot become authority evidence`() {
        assertThatThrownBy { authority.copy(sourceProvider = "untrusted provider") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { authority.copy(sourceReference = "record\nheader") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { authority.copy(expiresAt = at) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            authority.copy(expiresAt = at.plus(EvaluationViewerAuthority.MAX_LIFETIME).plusSeconds(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { authority.copy(expiresAt = at.plusNanos(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
