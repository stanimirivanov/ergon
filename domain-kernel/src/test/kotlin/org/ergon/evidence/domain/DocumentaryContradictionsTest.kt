package org.ergon.evidence.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DocumentaryContradictionsTest {
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val until = from.plusSeconds(100)
    private val supported =
        DocumentaryClaim(
            DocumentaryProperty.SUPPORT,
            DocumentaryValue.Support(true),
            "recovery",
            "staff",
            from,
            until,
        )
    private val denied = supported.copy(value = DocumentaryValue.Support(false))

    @Test
    fun `opposing booleans conflict only under exact applicability and known half open overlap`() {
        assertThat(DocumentaryContradictions.compare(supported, denied)).isEqualTo(ClaimComparison.CONFLICT)
        assertThat(DocumentaryContradictions.compare(denied, supported)).isEqualTo(ClaimComparison.CONFLICT)
        val adjacent = denied.copy(validFrom = until, validUntil = until.plusSeconds(1))
        assertThat(DocumentaryContradictions.compare(supported, adjacent)).isEqualTo(ClaimComparison.DISJOINT_VALIDITY)
        val overlapping = denied.copy(validFrom = until.minusNanos(1000), validUntil = until.plusSeconds(1))
        assertThat(DocumentaryContradictions.compare(supported, overlapping)).isEqualTo(ClaimComparison.CONFLICT)
        assertThat(DocumentaryContradictions.compare(supported, supported)).isEqualTo(ClaimComparison.COMPATIBLE)
    }

    @Test
    fun `unknown validity and differing text stay undetermined without inferred applicability`() {
        val unknown = supported.copy(validFrom = null, validUntil = null)
        assertThat(DocumentaryContradictions.compare(supported, unknown)).isEqualTo(ClaimComparison.UNDETERMINED)
        assertThat(DocumentaryContradictions.compare(unknown, denied)).isEqualTo(ClaimComparison.UNDETERMINED)
        assertThat(DocumentaryContradictions.compare(unknown, unknown)).isEqualTo(ClaimComparison.UNDETERMINED)
        for (other in listOf(denied.copy(scope = "external"), denied.copy(subject = "Recovery"))) {
            assertThat(DocumentaryContradictions.compare(supported, other)).isEqualTo(ClaimComparison.NOT_COMPARABLE)
        }
        val requirement =
            supported.copy(
                property = DocumentaryProperty.REQUIREMENT,
                value = DocumentaryValue.Requirement("MFA"),
            )
        assertThat(DocumentaryContradictions.compare(requirement, requirement)).isEqualTo(ClaimComparison.COMPATIBLE)
        val other = requirement.copy(value = DocumentaryValue.Requirement("Password"))
        assertThat(DocumentaryContradictions.compare(requirement, other))
            .isEqualTo(ClaimComparison.UNDETERMINED)
        assertThat(DocumentaryContradictions.compare(supported, requirement)).isEqualTo(ClaimComparison.NOT_COMPARABLE)
    }

    @Test
    fun `adjudication intent requires exact canonical candidates and an explicit bounded decision`() {
        val ids = listOf(UUID(0, 1), UUID(0, 2))
        val command =
            ContradictionCommand(
                UUID(0, 3),
                ids[0],
                2,
                ids,
                ContradictionChoice.PREFER_CLAIM,
                ids[0],
                "Checked",
            )
        for (change in listOf<() -> ContradictionCommand>(
            { command.copy(candidateIds = ids.reversed()) },
            { command.copy(candidateIds = ids + ids[0]) },
            { command.copy(expectedRevision = 0) },
            { command.copy(selectedClaimId = UUID(0, 8)) },
            { command.copy(choice = ContradictionChoice.DEFER) },
            { command.copy(selectedClaimId = null) },
            { command.copy(rationale = " ") },
            { command.copy(rationale = " padded") },
            { command.copy(rationale = "x".repeat(2001)) },
            { command.copy(rationale = "\u0000") },
            { command.copy(rationale = "\uD800") },
        )) {
            assertThatThrownBy { change() }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(command.copy(choice = ContradictionChoice.DEFER, selectedClaimId = null).selectedClaimId).isNull()
    }
}
