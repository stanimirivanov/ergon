package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkSourceParser
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.UUID

class CommonMarkSourceParserTest {
    private val parser = CommonMarkSourceParser()
    private val tenant = TenantId(UUID(0, 1))
    private val id = SourceId(UUID(0, 2))

    @Test
    fun `original Unicode and CRLF citations round trip in document preorder`() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/knowledge/attributed.md"))
        val text = resource.use { it.reader(Charsets.UTF_8).readText() }
        val bytes = text.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        val source = EvidenceSource.fromUtf8(tenant, id, EvidenceSourceFormat.COMMONMARK, bytes)
        val result = parser.parse(source)
        assertThat(result).isEqualTo(parser.parse(source))
        assertThat(result.extraction.extractorRevision).isEqualTo("commonmark-0.24.0-v1")
        assertThat(result.observations.map { it.kind }).containsExactly(
            SourceObservationKind.HEADING,
            SourceObservationKind.PARAGRAPH,
            SourceObservationKind.ORDERED_LIST,
            SourceObservationKind.PARAGRAPH,
            SourceObservationKind.PARAGRAPH,
            SourceObservationKind.PARAGRAPH,
        )
        assertThat(
            result.observations
                .first()
                .citation.quote,
        ).isEqualTo("# Guide 😀")
        assertThat(result.observations[1].citation.quote)
            .isEqualTo("Keep e\u0301 exact. [link](https://invalid.example/private)\r\ncontinued.")
        assertThat(result.observations[2].citation.quote).isEqualTo("3. First\r\n4. Second")
        assertThat(
            result.observations
                .last()
                .citation.quote,
        ).isEqualTo("Unclosed **markup")
        assertThat(result.observations.all { it.citation.verifyAgainst(source) }).isTrue()
        val changed = EvidenceSource.fromUtf8(TenantId(UUID(0, 3)), id, source.format, bytes)
        assertThat(parser.parse(changed).extraction.id).isNotEqualTo(result.extraction.id)
    }

    @Test
    fun `empty and HTML only sources produce no observations while inline HTML remains inert syntax`() {
        assertThat(parser.parse(source("")).observations).isEmpty()
        assertThat(parser.parse(source("<script>not a command</script>")).observations).isEmpty()
        val input = source("Click <b>this</b> ![image](file:///private) &amp; `code`\u0000")
        val result = parser.parse(input)
        assertThat(
            result.observations
                .single()
                .citation.quote,
        ).isEqualTo(input.text)
        assertThat(
            result.observations
                .single()
                .citation
                .verifyAgainst(input),
        ).isTrue()
    }

    @Test
    fun `nested lists and setext headings retain their complete original spans`() {
        val input = source("Title\n=====\n\n1. Outer\n   1. Inner\n\nTail 😀")
        val result = parser.parse(input)
        assertThat(
            result.observations
                .first()
                .citation.quote,
        ).isEqualTo("Title\n=====")
        assertThat(result.observations.count { it.kind == SourceObservationKind.ORDERED_LIST }).isEqualTo(2)
        assertThat(result.observations.all { it.citation.verifyAgainst(input) }).isTrue()
        assertThat(
            result.observations
                .last()
                .citation.quote,
        ).isEqualTo("Tail 😀")
    }

    @Test
    fun `unsupported formats and deterministic budgets fail without partial output`() {
        val other = EvidenceSource.fromUtf8(tenant, id, EvidenceSourceFormat.OPENAPI_3_0_JSON, "{}".toByteArray())
        assertThatThrownBy { parser.parse(other) }.isInstanceOf(InvalidSource::class.java)
        for (text in listOf(
            "a".repeat(65_537),
            "# h\n\n".repeat(257),
            "> ".repeat(33) + "deep",
            "*x* ".repeat(2500),
            "1. outer\n   1. middle\n      1. " + "x".repeat(40_000),
        )) {
            assertThatThrownBy { parser.parse(source(text)) }.isInstanceOf(InvalidSource::class.java)
        }
    }

    private fun source(text: String): EvidenceSource =
        EvidenceSource.fromUtf8(tenant, id, EvidenceSourceFormat.COMMONMARK, text.toByteArray(Charsets.UTF_8))
}
