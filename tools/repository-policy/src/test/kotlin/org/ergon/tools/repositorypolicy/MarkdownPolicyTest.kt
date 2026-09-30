package org.ergon.tools.repositorypolicy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MarkdownPolicyTest {
    @Test
    fun `parser exposes rendered structure without treating hidden content as prose`() {
        val document =
            MarkdownDocument.parse(
                "docs/guide.md",
                """# Guide

## Visible heading

Rendered words.

<!-- hidden words -->

![image-only words](image.png)

```markdown
## Fenced heading
fenced words
```

## Visible heading
""",
            )

        assertThat(document.headings.map { it.text }).containsExactly("Guide", "Visible heading", "Visible heading")
        assertThat(document.headings.map { it.anchor }).containsExactly("guide", "visible-heading", "visible-heading-1")
        assertThat(document.visibleText())
            .contains("Rendered words")
            .doesNotContain("hidden words", "image-only", "fenced words")
    }

    @Test
    fun `local link validation accepts exact destinations and rendered anchors`() {
        val repository =
            repository(
                mapOf(
                    "docs/source.md" to "[Target](Target.md#visible-heading)",
                    "docs/Target.md" to "# Target\n\n## Visible heading\n\nText.",
                ),
            )

        assertThat(LinkPolicy.check(repository)).isEmpty()
    }

    @Test
    fun `local link validation reports casing anchors containment and missing files`() {
        val repository =
            repository(
                mapOf(
                    "docs/source.md" to
                        """[Case](target.md)
[Anchor](Target.md#missing)
[Escape](../../outside.md)
[Missing](absent.md)
""",
                    "docs/Target.md" to "# Target",
                ),
            )

        assertThat(LinkPolicy.check(repository).map { it.rule })
            .containsExactlyInAnyOrder(
                "markdown.link.casing",
                "markdown.link.anchor",
                "markdown.link.containment",
                "markdown.link.destination",
            )
    }

    @Test
    fun `long guide accepts a first visible non-empty summary`() {
        val repository =
            repository(
                mapOf(
                    "docs/guide.md" to longGuide("## TL;DR\n\nA useful summary."),
                ),
            )

        assertThat(TldrPolicy.check(repository)).isEmpty()
    }

    @Test
    fun `hidden image and fenced content cannot satisfy summary policy`() {
        val variants =
            listOf(
                "## <span>TL;DR</span>\n\nSummary.",
                "## TL;DR\n\n![Summary only](image.png)",
                "```markdown\nbackground\n```\n\n## TL;DR\n\nSummary.",
            )

        variants.forEach { summary ->
            val violations = TldrPolicy.check(repository(mapOf("docs/guide.md" to longGuide(summary))))
            assertThat(violations.map { it.rule }).contains("markdown.tldr")
        }
    }

    private fun longGuide(summary: String): String =
        buildString {
            appendLine("# Guide")
            appendLine()
            appendLine(summary)
            repeat(6) { index ->
                appendLine()
                appendLine("## Section $index")
                appendLine()
                appendLine("Text.")
            }
        }

    private fun repository(documents: Map<String, String>): Repository =
        Repository.fixture(Path.of("."), documents, setOf("docs/image.png"))
}
