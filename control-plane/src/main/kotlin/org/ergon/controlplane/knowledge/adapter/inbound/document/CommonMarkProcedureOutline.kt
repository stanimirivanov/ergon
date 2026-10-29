package org.ergon.controlplane.knowledge.adapter.inbound.document

import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.ergon.controlplane.evidence.application.InvalidProcedure
import org.ergon.controlplane.evidence.application.ProcedureOutlineParser
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.ProcedureContent
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceSpan
import org.springframework.stereotype.Component

/** Only flat ordered lists with one paragraph per item are supported; raw inline syntax is never rendered. */
@Component
class CommonMarkProcedureOutline : ProcedureOutlineParser {
    override fun steps(
        source: EvidenceSource,
        selected: SourceCitation,
    ): List<SourceCitation> {
        if (source.format != EvidenceSourceFormat.COMMONMARK || !selected.verifyAgainst(source) ||
            source.text.toByteArray(Charsets.UTF_8).size > SourceRevisionService.MAX_BYTES
        ) {
            throw InvalidProcedure()
        }
        val list = findList(source, selected)
        requireTopLevel(list)
        return items(source, list)
    }

    private fun findList(
        source: EvidenceSource,
        selected: SourceCitation,
    ): OrderedList {
        val root =
            Parser
                .builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build()
                .parse(source.text)
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.add(root to 0)
        var found: OrderedList? = null
        var visited = 0
        val maxNodes = CommonMarkSourceParser.MAX_NODES
        val maxDepth = CommonMarkSourceParser.MAX_DEPTH
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (++visited > maxNodes || depth > maxDepth) throw InvalidProcedure()
            if (node is OrderedList && citation(source, node) == selected) found = node
            var child = node.lastChild
            while (child != null) {
                pending.add(child to depth + 1)
                child = child.previous
            }
        }
        return found ?: throw InvalidProcedure()
    }

    private fun requireTopLevel(list: OrderedList) {
        // A nested list is not an independently supported procedure selection.
        var parent = list.parent
        while (parent != null) {
            if (parent is ListItem) throw InvalidProcedure()
            parent = parent.parent
        }
    }

    private fun items(
        source: EvidenceSource,
        list: OrderedList,
    ): List<SourceCitation> {
        val steps = mutableListOf<SourceCitation>()
        var item = list.firstChild
        while (item != null) {
            if (steps.size >= ProcedureContent.MAX_STEPS) throw InvalidProcedure()
            requireSingleParagraph(item)
            steps.add(citation(source, item))
            item = item.next
        }
        if (steps.isEmpty()) throw InvalidProcedure()
        return steps.toList()
    }

    private fun requireSingleParagraph(item: Node) {
        if (item !is ListItem) throw InvalidProcedure()
        val paragraph = item.firstChild
        if (paragraph !is Paragraph || paragraph.next != null) throw InvalidProcedure()
    }

    private fun citation(
        source: EvidenceSource,
        node: Node,
    ): SourceCitation {
        val spans = node.sourceSpans
        check(spans.isNotEmpty()) { "procedure block has no source span" }
        val start = spans.first().inputIndex
        val end = spans.last().inputIndex + spans.last().length
        val text = source.text
        val result =
            SourceCitation(
                source.revision,
                SourceSpan(text.codePointCount(0, start), text.codePointCount(0, end)),
                text.substring(start, end),
            )
        check(result.verifyAgainst(source)) { "procedure block citation failed verification" }
        return result
    }
}
