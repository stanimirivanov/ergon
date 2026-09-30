package org.ergon.tools.repositorypolicy

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.util.Locale

internal data class MarkdownHeading(
    val level: Int,
    val line: Int,
    val text: String,
    val anchor: String,
    val containsRawHtml: Boolean,
)

internal data class MarkdownLink(
    val line: Int,
    val destination: String,
)

internal data class MarkdownCodeBlock(
    val line: Int,
    val info: String,
    val body: String,
)

internal data class MarkdownTableCell(
    val text: String,
    val destinations: List<String>,
    val containsRawHtml: Boolean,
)

internal data class MarkdownTableRow(
    val line: Int,
    val cells: List<MarkdownTableCell>,
)

internal data class MarkdownTable(
    val line: Int,
    val rows: List<MarkdownTableRow>,
)

private data class MarkdownStructure(
    val headings: List<MarkdownHeading>,
    val links: List<MarkdownLink>,
    val codeBlocks: List<MarkdownCodeBlock>,
    val tables: List<MarkdownTable>,
)

internal class MarkdownDocument private constructor(
    val relativePath: String,
    val body: String,
    private val structure: MarkdownStructure,
    private val visibleLines: Map<Int, String>,
    val wordCount: Int,
) {
    val headings: List<MarkdownHeading> get() = structure.headings
    val links: List<MarkdownLink> get() = structure.links
    val codeBlocks: List<MarkdownCodeBlock> get() = structure.codeBlocks
    val tables: List<MarkdownTable> get() = structure.tables

    fun visibleText(
        startLineInclusive: Int = 1,
        endLineExclusive: Int = Int.MAX_VALUE,
    ): String =
        visibleLines
            .asSequence()
            .filter { (line, _) -> line in startLineInclusive until endLineExclusive }
            .sortedBy { (line, _) -> line }
            .joinToString(" ") { (_, text) -> text }
            .trim()

    fun sectionText(heading: MarkdownHeading): String {
        val end =
            headings
                .asSequence()
                .filter { it.line > heading.line && it.level <= heading.level }
                .map { it.line }
                .firstOrNull() ?: Int.MAX_VALUE
        return visibleText(heading.line + 1, end)
    }

    fun firstTableAfter(heading: MarkdownHeading): MarkdownTable? {
        val end =
            headings
                .asSequence()
                .filter { it.line > heading.line && it.level <= heading.level }
                .map { it.line }
                .firstOrNull() ?: Int.MAX_VALUE
        return tables.firstOrNull { it.line in (heading.line + 1) until end }
    }

    companion object {
        private val parser =
            Parser
                .builder()
                .extensions(listOf(TablesExtension.create()))
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build()

        fun parse(
            relativePath: String,
            body: String,
        ): MarkdownDocument {
            val root = parser.parse(body)
            val collector = Collector()
            collector.collect(root)
            return MarkdownDocument(
                relativePath = relativePath,
                body = body,
                structure =
                    MarkdownStructure(
                        headings = collector.headings,
                        links = collector.links,
                        codeBlocks = collector.codeBlocks,
                        tables = collector.tables,
                    ),
                visibleLines = collector.visibleLines.mapValues { (_, chunks) -> chunks.joinToString("") },
                wordCount = WORD.findAll(collector.words.joinToString(" ")).count(),
            )
        }

        private val WORD = Regex("[\\p{L}\\p{N}]+")
    }
}

private class Collector {
    val headings = mutableListOf<MarkdownHeading>()
    val links = mutableListOf<MarkdownLink>()
    val codeBlocks = mutableListOf<MarkdownCodeBlock>()
    val tables = mutableListOf<MarkdownTable>()
    val visibleLines = sortedMapOf<Int, MutableList<String>>()
    val words = mutableListOf<String>()
    private val anchors = mutableMapOf<String, Int>()

    fun collect(root: Node) {
        visitChildren(root)
    }

    private fun visit(node: Node) {
        when (node) {
            is Heading -> {
                val text = renderedText(node).trim()
                headings +=
                    MarkdownHeading(
                        level = node.level,
                        line = node.line(),
                        text = text,
                        anchor = uniqueAnchor(text),
                        containsRawHtml = node.containsRawHtml(),
                    )
                visitChildren(node)
            }

            is Link -> {
                links += MarkdownLink(node.line(), node.destination)
                visitChildren(node)
            }

            is Image -> {
                links += MarkdownLink(node.line(), node.destination)
            }

            is FencedCodeBlock -> {
                codeBlocks += MarkdownCodeBlock(node.line(), node.info.orEmpty(), node.literal.orEmpty())
            }

            is IndentedCodeBlock, is HtmlBlock, is HtmlInline -> {
                return
            }

            is TableBlock -> {
                tables += readTable(node)
                visitChildren(node)
            }

            is Text -> {
                addVisible(node.line(), node.literal)
            }

            is Code -> {
                addVisible(node.line(), node.literal)
            }

            is SoftLineBreak, is HardLineBreak -> {
                addVisible(node.line(), " ")
            }

            else -> {
                visitChildren(node)
            }
        }
    }

    private fun visitChildren(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            val next = child.next
            visit(child)
            child = next
        }
    }

    private fun addVisible(
        line: Int,
        value: String,
    ) {
        val parts = value.split('\n')
        parts.forEachIndexed { index, part ->
            val targetLine = line + index
            visibleLines.getOrPut(targetLine) { mutableListOf() }.add(part)
            words += part
        }
    }

    private fun uniqueAnchor(text: String): String {
        val base =
            text
                .lowercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .trim()
                .replace(Regex("\\s+"), "-")
        val occurrence = anchors.getOrDefault(base, 0)
        anchors[base] = occurrence + 1
        return if (occurrence == 0) base else "$base-$occurrence"
    }

    private fun readTable(block: TableBlock): MarkdownTable {
        val rows = mutableListOf<MarkdownTableRow>()
        block.descendants(TableRow::class.java).forEach { row ->
            val cells =
                row.children(TableCell::class.java).map { cell ->
                    MarkdownTableCell(
                        text = renderedText(cell).trim(),
                        destinations = cell.descendants(Link::class.java).map { it.destination },
                        containsRawHtml = cell.containsRawHtml(),
                    )
                }
            rows += MarkdownTableRow(row.line(), cells)
        }
        return MarkdownTable(block.line(), rows)
    }
}

private fun Node.line(): Int = sourceSpans.firstOrNull()?.lineIndex?.plus(1) ?: 1

private fun <T : Node> Node.children(type: Class<T>): List<T> {
    val result = mutableListOf<T>()
    var child = firstChild
    while (child != null) {
        if (type.isInstance(child)) result += type.cast(child)
        child = child.next
    }
    return result
}

private fun <T : Node> Node.descendants(type: Class<T>): List<T> {
    val result = mutableListOf<T>()

    fun descend(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            if (type.isInstance(child)) result += type.cast(child)
            descend(child)
            child = child.next
        }
    }
    descend(this)
    return result
}

private fun renderedText(parent: Node): String {
    val result = StringBuilder()

    fun append(node: Node) {
        when (node) {
            is Text -> {
                result.append(node.literal)
            }

            is Code -> {
                result.append(node.literal)
            }

            is SoftLineBreak, is HardLineBreak -> {
                result.append(' ')
            }

            is Image, is HtmlBlock, is HtmlInline, is FencedCodeBlock, is IndentedCodeBlock -> {
                return
            }

            else -> {
                var child = node.firstChild
                while (child != null) {
                    append(child)
                    child = child.next
                }
            }
        }
    }
    append(parent)
    return result.toString()
}

private fun Node.containsRawHtml(): Boolean =
    this is HtmlBlock ||
        this is HtmlInline ||
        generateSequence(firstChild) { child -> child.next }.any { child -> child.containsRawHtml() }
