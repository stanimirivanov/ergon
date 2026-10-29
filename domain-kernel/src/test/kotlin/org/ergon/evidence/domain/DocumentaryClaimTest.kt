package org.ergon.evidence.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class DocumentaryClaimTest {
    private val claim =
        DocumentaryClaim(
            DocumentaryProperty.SUPPORT,
            DocumentaryValue.Support(false),
            "sso",
            "employees",
        )

    @Test
    fun `typed values cannot replace operational facts or swap registered meanings`() {
        assertThat(claim.value).isEqualTo(DocumentaryValue.Support(false))
        assertThat(claim.property.key).startsWith("documentation.")
        assertThatThrownBy { claim.copy(value = DocumentaryValue.Requirement("true")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        for (invalid in listOf("", " ", "x".repeat(1001), "line\nline")) {
            assertThatThrownBy { DocumentaryValue.Requirement(invalid) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        for (label in listOf("", " ", " padded", "line\nline", "x".repeat(257))) {
            assertThatThrownBy { claim.copy(subject = label) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { claim.copy(scope = label) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `unknown validity stays distinct from a bounded half open interval`() {
        val start = Instant.parse("2026-10-29T00:00:00Z")
        assertThat(claim.validFrom).isNull()
        assertThat(claim.validUntil).isNull()
        assertThat(claim.copy(validFrom = start, validUntil = start.plusSeconds(1)).validFrom).isEqualTo(start)
        for ((from, until) in listOf(
            start to null,
            null to start,
            start to start,
            start to start.minusSeconds(1),
            start.plusNanos(1) to start.plusSeconds(1),
            start to Instant.MAX,
        )) {
            assertThatThrownBy { claim.copy(validFrom = from, validUntil = until) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
