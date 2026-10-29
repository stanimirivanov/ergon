package org.ergon.evidence.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EvidenceBundleTest {
    private val source =
        EvidenceSource.fromUtf8(
            TenantId(UUID(0, 1)),
            SourceId(UUID(0, 2)),
            EvidenceSourceFormat.COMMONMARK,
            "text".toByteArray(),
        )
    private val start = Instant.parse("2026-10-29T12:00:00Z")
    private val first = proposal(10, true)
    private val second = proposal(11, false)

    @Test
    fun `known conflicting sets require exactly their explicit preferred selection`() {
        val findings = EvidenceBundleQualification.qualify(listOf(first, second), setOf(first.id), first.id)
        assertThat(findings.single().comparison).isEqualTo(ClaimComparison.CONFLICT)
        for (attempt in listOf<() -> Any>(
            { EvidenceBundleQualification.qualify(listOf(first, second), setOf(first.id), null) },
            { EvidenceBundleQualification.qualify(listOf(first, second), setOf(first.id), second.id) },
            { EvidenceBundleQualification.qualify(listOf(first, second), setOf(first.id, second.id), first.id) },
            { EvidenceBundleQualification.qualify(listOf(first, second), emptySet(), first.id) },
        )) {
            assertThatThrownBy { attempt() }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `unknown validity and undetermined textual meaning cannot be qualified even by preference`() {
        val unknown = first.copy(claim = first.claim.copy(validFrom = null, validUntil = null))
        assertThatThrownBy { EvidenceBundleQualification.qualify(listOf(unknown), setOf(first.id), null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val requirement =
            first.claim.copy(
                property = DocumentaryProperty.REQUIREMENT,
                value = DocumentaryValue.Requirement("MFA"),
            )
        val left = first.copy(claim = requirement)
        val right = second.copy(claim = requirement.copy(value = DocumentaryValue.Requirement("Password")))
        assertThatThrownBy { EvidenceBundleQualification.qualify(listOf(left, right), setOf(left.id), left.id) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `compatible and disjoint known intervals qualify without an inferred winner`() {
        val equal = second.copy(claim = first.claim)
        assertThat(
            EvidenceBundleQualification
                .qualify(
                    listOf(first, equal),
                    setOf(first.id, equal.id),
                    null,
                ).single()
                .comparison,
        ).isEqualTo(ClaimComparison.COMPATIBLE)
        val later = second.claim.copy(validFrom = start.plusSeconds(60), validUntil = start.plusSeconds(120))
        val disjoint = second.copy(claim = later)
        assertThat(
            EvidenceBundleQualification
                .qualify(
                    listOf(first, disjoint),
                    setOf(first.id, second.id),
                    null,
                ).single()
                .comparison,
        ).isEqualTo(ClaimComparison.DISJOINT_VALIDITY)
    }

    @Test
    fun `publication intent is bounded canonical and exact`() {
        val command =
            EvidenceBundleCommand(
                UUID(0, 30),
                listOf(first.id),
                emptyList(),
                listOf(BundleClaimSetSelection(first.id, 2, listOf(first.id, second.id), UUID(0, 40))),
            )
        for (attempt in listOf<() -> Any>(
            { command.copy(claims = emptyList()) },
            { command.copy(claims = listOf(first.id, first.id)) },
            { command.copy(claims = listOf(second.id, first.id)) },
            { command.copy(claims = (1L..21L).map { UUID(0, it) }) },
            { command.claimSets.single().copy(candidateIds = listOf(second.id)) },
            { command.claimSets.single().copy(revision = 0) },
        )) {
            assertThatThrownBy { attempt() }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun proposal(
        id: Long,
        value: Boolean,
    ) = ClaimProposal(
        UUID(0, id),
        EvidenceExtraction(UUID(0, 3), source.revision, "test-v1"),
        0,
        SourceCitation(source.revision, SourceSpan(0, 4), "text"),
        DocumentaryClaim(
            DocumentaryProperty.SUPPORT,
            DocumentaryValue.Support(
                value,
            ),
            "recovery",
            "staff",
            start,
            start.plusSeconds(60),
        ),
    )
}
