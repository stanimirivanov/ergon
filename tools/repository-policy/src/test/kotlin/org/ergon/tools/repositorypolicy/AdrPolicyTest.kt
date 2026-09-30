package org.ergon.tools.repositorypolicy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AdrPolicyTest {
    @Test
    fun `current history satisfies sequence format index and high-water policy`() {
        assertThat(AdrPolicy.check(repository())).isEmpty()
    }

    @Test
    fun `deleting the published tail fails sequence and index policy`() {
        val documents = documents().toMutableMap()
        documents.remove("docs/decisions/0042-decision-42.md")

        assertThat(AdrPolicy.check(repository(documents)).map { it.rule })
            .contains("adr.sequence", "adr.index")
    }

    @Test
    fun `new decisions cannot use a grandfathered heading or omit current metadata`() {
        val documents = documents().toMutableMap()
        documents["docs/decisions/0042-decision-42.md"] =
            historicalRecord(42).replace("## Alternatives considered", "## Alternatives")

        assertThat(AdrPolicy.check(repository(documents)).map { it.rule })
            .contains("adr.heading", "adr.metadata", "adr.sections")
    }

    @Test
    fun `index duplicate and wrong filename are reported`() {
        val documents = documents().toMutableMap()
        documents["docs/decisions/README.md"] =
            documents
                .getValue("docs/decisions/README.md")
                .replace(
                    "| 0042 | [Decision 42](0042-decision-42.md) |",
                    """| 0042 | [Decision 42](wrong.md) |
| 0042 | [Decision 42 again](0042-decision-42.md) |""",
                )

        assertThat(AdrPolicy.check(repository(documents)).map { it.rule }).contains("adr.index")
    }

    @Test
    fun `supersession must name an existing reciprocal record`() {
        val documents = documents().toMutableMap()
        documents["docs/decisions/0042-decision-42.md"] =
            documents
                .getValue("docs/decisions/0042-decision-42.md")
                .replace("- Supersedes:", "- Supersedes: ADR 9999")

        assertThat(AdrPolicy.check(repository(documents)).map { it.rule }).contains("adr.supersession")
    }

    private fun repository(documents: Map<String, String> = documents()): Repository =
        Repository.fixture(
            Path.of("."),
            documents,
        )

    private fun documents(): Map<String, String> {
        val documents = linkedMapOf<String, String>()
        (1..AdrPolicy.LATEST_PUBLISHED_ADR).forEach { number ->
            val path = "docs/decisions/${number.toString().padStart(4, '0')}-decision-$number.md"
            documents[path] = if (number == 42) CURRENT_RECORD else historicalRecord(number)
        }
        documents["docs/decisions/0000-template.md"] = TEMPLATE
        documents["docs/decisions/README.md"] = index()
        return documents
    }

    private fun historicalRecord(number: Int): String {
        val padded = number.toString().padStart(4, '0')
        val heading = if (number <= 6) "# ADR $padded: Decision $number" else "# $padded. Decision $number"
        val status = if (number <= 5) "Superseded by the Ergon rewrite" else "proposed"
        val sections =
            when (number) {
                in 1..5 -> {
                    listOf("Context", "Decision", "Consequences")
                }

                in 6..28 -> {
                    listOf("Context", "Decision", "Alternatives", "Consequences", "Verification")
                }

                in 29..31 -> {
                    listOf(
                        "Context",
                        "Decision",
                        "Alternatives",
                        "Consequences",
                        "Compatibility and migration",
                        "Validation",
                    )
                }

                else -> {
                    listOf(
                        "TL;DR",
                        "Context",
                        "Decision",
                        "Alternatives considered",
                        "Consequences",
                        "Compatibility and migration",
                        "Security and operations",
                        "Validation",
                    )
                }
            }
        return buildString {
            appendLine(heading)
            appendLine()
            appendLine("- Status: $status")
            appendLine("- Date: 2026-09-01")
            sections.forEach { section ->
                appendLine()
                appendLine("## $section")
                appendLine()
                appendLine("Visible decision text.")
            }
        }
    }

    private companion object {
        const val CURRENT_RECORD =
            """# ADR 0042: Decision 42

- Status: Proposed
- Date: 2026-09-29
- Milestone: M01 - Resolution foundation
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

Context.

## Decision

Decision.

## Alternatives considered

Alternative.

## Consequences

Consequences.

## Compatibility and migration

Compatibility.

## Security and operations

Security.

## Validation

Validation.
"""

        const val TEMPLATE =
            """# ADR NNNN: Decision title

- Status: Proposed
- Date: YYYY-MM-DD
- Milestone: MNN - Outcome
- Deciders: names or roles
- Supersedes:
- Superseded by:

## Context

Text.

## Decision

Text.

## Alternatives considered

Text.

## Consequences

Text.

## Compatibility and migration

Text.

## Security and operations

Text.

## Validation

Text.
"""
    }

    private fun index(): String =
        buildString {
            appendLine("# Architecture decision records")
            appendLine()
            appendLine("## Index")
            appendLine()
            appendLine("| ADR | Decision |")
            appendLine("|:--|:--|")
            (1..AdrPolicy.LATEST_PUBLISHED_ADR).forEach { number ->
                val padded = number.toString().padStart(4, '0')
                appendLine("| $padded | [Decision $number]($padded-decision-$number.md) |")
            }
        }
}
