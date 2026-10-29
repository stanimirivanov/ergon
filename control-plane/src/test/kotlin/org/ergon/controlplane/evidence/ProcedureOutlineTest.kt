package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.out.persistence.ProcedureDefinitionCodec
import org.ergon.controlplane.evidence.application.InvalidProcedure
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkProcedureOutline
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkSourceParser
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.evidence.domain.SourceSpan
import org.junit.jupiter.api.Test
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper

class ProcedureOutlineTest {
    private val fixture = ProcedureFixtures()
    private val outline = CommonMarkProcedureOutline()

    @Test
    fun `steps preserve source order markers Unicode CRLF and inert inline syntax`() {
        val proposal = fixture.proposal()
        val steps = proposal.content.steps
        assertThat(steps.map { it.position }).containsExactly(1, 2)
        assertThat(steps[0].citation.quote).isEqualTo("3. Read 😀\r\n   details.")
        assertThat(steps[1].citation.quote).startsWith("4. Call [help](https://invalid.example)")
        assertThat(steps[1].citation.quote).contains("<b>carefully</b>")
        assertThat(steps.all { it.citation.verifyAgainst(fixture.markdown) }).isTrue()
        assertThat(proposal.content.citation.verifyAgainst(fixture.markdown)).isTrue()
        assertThat(
            steps
                .first()
                .operation
                ?.citation
                ?.verifyAgainst(fixture.api),
        ).isTrue()
        assertThat(steps.last().operation).isNull()
        assertThat(fixture.proposal()).isEqualTo(proposal)
    }

    @Test
    fun `nested mixed empty code and multi paragraph lists cannot silently become procedures`() {
        val cases =
            listOf(
                "1. Outer\n   1. Inner",
                "1. Outer\n   - Inner",
                "1.\n",
                "1. ```\n   code\n   ```",
                "1. <div>html</div>",
                "1. First\n\n   second paragraph",
                "- unordered",
                (1..21).joinToString("\n") { "$it. Step" },
                "> ".repeat(35) + "1. Deep",
            )
        for (text in cases) {
            val source = source(text)
            val selected =
                if (text.startsWith(">")) {
                    rawCitation(source)
                } else {
                    CommonMarkSourceParser()
                        .parse(source)
                        .observations
                        .firstOrNull { it.kind == SourceObservationKind.ORDERED_LIST }
                        ?.citation ?: rawCitation(source)
                }
            assertThatThrownBy { outline.steps(source, selected) }.isInstanceOf(InvalidProcedure::class.java)
        }
        val nested = source("1. Outer\n   1. Inner")
        val nestedObservations = CommonMarkSourceParser().parse(nested).observations
        val inner = nestedObservations.last { it.kind == SourceObservationKind.ORDERED_LIST }
        assertThatThrownBy { outline.steps(nested, inner.citation) }.isInstanceOf(InvalidProcedure::class.java)
        assertThatThrownBy { outline.steps(fixture.api, rawCitation(fixture.api)) }
            .isInstanceOf(InvalidProcedure::class.java)
    }

    @Test
    fun `versioned persistence round trips linked and unlinked steps without using wire types`() {
        val content = fixture.proposal().content
        val json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
        val codec = ProcedureDefinitionCodec(json)
        val encoded = codec.encode(content)
        assertThat(codec.decode(fixture.markdown.revision, content.title, encoded)).isEqualTo(content)
        assertThat(encoded).contains("\"operation\":null", "\"sourceId\":").doesNotContain("\"source_id\":")
        assertThatThrownBy { codec.decode(fixture.markdown.revision, content.title, "{}") }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            codec.decode(
                fixture.markdown.revision,
                content.title,
                encoded.replace("\"start\":${content.citation.span.start}", "\"start\":-1"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun source(text: String): EvidenceSource =
        EvidenceSource.fromUtf8(
            fixture.tenant,
            fixture.markdown.revision.sourceId,
            EvidenceSourceFormat.COMMONMARK,
            text.toByteArray(),
        )

    private fun rawCitation(source: EvidenceSource): SourceCitation =
        SourceCitation(
            source.revision,
            SourceSpan(0, source.text.codePointCount(0, source.text.length)),
            source.text,
        )
}
