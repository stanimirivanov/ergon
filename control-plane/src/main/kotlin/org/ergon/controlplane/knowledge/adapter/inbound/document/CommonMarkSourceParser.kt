package org.ergon.controlplane.knowledge.adapter.inbound.document

import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.controlplane.knowledge.application.SourceCompilation
import org.ergon.controlplane.knowledge.application.SourceObservationParser
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceObservation
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.evidence.domain.SourceSpan
import org.springframework.stereotype.Component
import java.util.UUID

/** Bounded CommonMark syntax projection. No renderer, extension, network resolver or semantic binder. */
@Component
class CommonMarkSourceParser : SourceObservationParser {
    companion object {
        const val REVISION = "commonmark-0.24.0-v1"
        const val MAX_DEPTH = 32
        const val MAX_NODES = 4096
        const val MAX_OBSERVATIONS = 256
        const val MAX_QUOTED_CODE_POINTS = 131_072
    }

    override fun parse(source: EvidenceSource): SourceCompilation {
        validateSource(source)
        val root =
            Parser
                .builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build()
                .parse(source.text)
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.add(root to 0)
        val observations = mutableListOf<SourceObservation>()
        var visited = 0
        var quoted = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (++visited > MAX_NODES || depth > MAX_DEPTH) throw InvalidSource()
            val kind = kind(node)
            if (kind != null) {
                val citation = citation(source, node)
                quoted += citation.span.endExclusive - citation.span.start
                if (observations.size >= MAX_OBSERVATIONS || quoted > MAX_QUOTED_CODE_POINTS) throw InvalidSource()
                observations.add(SourceObservation(kind, citation))
            }
            var child = node.lastChild
            while (child != null) {
                pending.add(child to depth + 1)
                child = child.previous
            }
        }
        val revision = source.revision
        val identity = "$REVISION:${revision.tenantId.value}:${revision.sourceId.value}:${revision.digest.value}"
        return SourceCompilation(
            EvidenceExtraction(UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)), revision, REVISION),
            observations.toList(),
        )
    }

    private fun validateSource(source: EvidenceSource) {
        if (source.format != EvidenceSourceFormat.COMMONMARK ||
            source.text.toByteArray(Charsets.UTF_8).size > SourceRevisionService.MAX_BYTES
        ) {
            throw InvalidSource()
        }
    }

    private fun kind(node: Node): SourceObservationKind? =
        when (node) {
            is Heading -> SourceObservationKind.HEADING
            is Paragraph -> SourceObservationKind.PARAGRAPH
            is OrderedList -> SourceObservationKind.ORDERED_LIST
            else -> null
        }

    private fun citation(
        source: EvidenceSource,
        node: Node,
    ): SourceCitation {
        val spans = node.sourceSpans
        check(spans.isNotEmpty()) { "CommonMark block has no source span" }
        val start = spans.first().inputIndex
        val end = spans.last().inputIndex + spans.last().length
        val text = source.text
        val citation =
            SourceCitation(
                source.revision,
                SourceSpan(text.codePointCount(0, start), text.codePointCount(0, end)),
                text.substring(start, end),
            )
        check(citation.verifyAgainst(source)) { "CommonMark citation failed verification" }
        return citation
    }
}
