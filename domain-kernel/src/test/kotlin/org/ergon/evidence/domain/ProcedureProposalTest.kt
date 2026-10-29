package org.ergon.evidence.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.UUID

class ProcedureProposalTest {
    private val source =
        EvidenceSource.fromUtf8(
            TenantId(UUID(0, 1)),
            SourceId(UUID(0, 2)),
            EvidenceSourceFormat.COMMONMARK,
            "1. A\n2. B".toByteArray(),
        )
    private val whole = SourceCitation(source.revision, SourceSpan(0, 9), source.text)
    private val first = ProcedureStep(1, SourceCitation(source.revision, SourceSpan(0, 4), "1. A"))
    private val second = ProcedureStep(2, SourceCitation(source.revision, SourceSpan(5, 9), "2. B"))

    @Test
    fun `content snapshots ordered steps and does not expose a mutable list`() {
        val input = mutableListOf(first, second)
        val content = ProcedureContent.create("Restore", whole, input)
        input.clear()
        assertThat(content.steps).containsExactly(first, second)
        assertThatThrownBy { (content.steps as MutableList<ProcedureStep>).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `invalid ordering foreign steps malformed titles and NUL quotes are rejected`() {
        for (steps in listOf(
            emptyList(),
            listOf(second, first),
            listOf(first, first),
            listOf(first, second.copy(citation = first.citation)),
            List(21) { first },
        )) {
            assertThatThrownBy { ProcedureContent.create("Restore", whole, steps) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        for (title in listOf("", " padded", "line\nline", "x".repeat(257), "\ud800")) {
            assertThatThrownBy { ProcedureContent.create(title, whole, listOf(first, second)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        val foreignSource = source.revision.copy(tenantId = TenantId(UUID(0, 3)))
        val foreign = second.copy(citation = second.citation.copy(revision = foreignSource))
        assertThatThrownBy { ProcedureContent.create("Restore", whole, listOf(first, foreign)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val nul = first.copy(citation = first.citation.copy(quote = "\u0000"))
        assertThatThrownBy { ProcedureContent.create("Restore", whole, listOf(nul, second)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `operation references retain source identity and respect aggregate quote budgets`() {
        val other = source.revision.copy(tenantId = TenantId(UUID(0, 3)))
        val citation = whole.copy(revision = other)
        val operation =
            DocumentedOperation(
                EvidenceExtraction(
                    UUID(0, 4),
                    other,
                    "api-v1",
                ),
                0,
                citation,
                "/paths/~1test/get",
            )
        assertThatThrownBy {
            ProcedureContent.create(
                "Restore",
                whole,
                listOf(first.copy(operation = operation), second),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        val large = whole.copy(quote = "x".repeat(ProcedureContent.MAX_QUOTED_BYTES))
        val largeOperation =
            DocumentedOperation(
                EvidenceExtraction(UUID(0, 4), source.revision, "api-v1"),
                0,
                large,
                "/paths/~1test/get",
            )
        assertThatThrownBy {
            ProcedureContent.create(
                "Restore",
                whole,
                listOf(first.copy(operation = largeOperation), second),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
