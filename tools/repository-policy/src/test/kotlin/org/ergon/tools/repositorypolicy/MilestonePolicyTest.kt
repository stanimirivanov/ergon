package org.ergon.tools.repositorypolicy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MilestonePolicyTest {
    @Test
    fun `published milestone index and boundaries agree`() {
        assertThat(MilestonePolicy.check(repository(roadmap()))).isEmpty()
    }

    @Test
    fun `deleting latest milestone is detected by retained high-water mark`() {
        val roadmap = roadmap().replace(Regex("(?s)\\| M08.*?## M08.*?(?=## Planning rules)"), "")

        assertThat(MilestonePolicy.check(repository(roadmap)).map { it.rule }).contains("milestone.sequence")
    }

    @Test
    fun `missing message and mismatched boundary are actionable failures`() {
        val roadmap =
            roadmap()
                .replace("| M03 - Outcome 3 | Message 3. |", "| M03 - Outcome 3 | <span>Message 3.</span> |")
                .replace("## M04 - Outcome 4", "## M04 - Different outcome")

        assertThat(MilestonePolicy.check(repository(roadmap)).map { it.rule })
            .contains("milestone.message", "milestone.boundaries")
    }

    @Test
    fun `code fenced fake boundary does not satisfy roadmap`() {
        val roadmap =
            roadmap()
                .replace("## M05 - Outcome 5", "```markdown\n## M05 - Outcome 5\n```\n\n## Removed M05")

        assertThat(MilestonePolicy.check(repository(roadmap)).map { it.rule }).contains("milestone.boundaries")
    }

    private fun repository(body: String): Repository {
        val documents = mapOf("docs/roadmap/milestones.md" to body)
        return Repository.fixture(Path.of("."), documents)
    }

    private fun roadmap(): String =
        buildString {
            appendLine("# Milestones")
            appendLine()
            appendLine("## Milestone index")
            appendLine()
            appendLine("| Milestone title | Short message |")
            appendLine("|:--|:--|")
            (1..MilestonePolicy.LATEST_PUBLISHED_MILESTONE).forEach { number ->
                val padded = number.toString().padStart(2, '0')
                appendLine("| M$padded - Outcome $number | Message $number. |")
            }
            (1..MilestonePolicy.LATEST_PUBLISHED_MILESTONE).forEach { number ->
                val padded = number.toString().padStart(2, '0')
                appendLine()
                appendLine("## M$padded - Outcome $number")
                appendLine()
                appendLine("**Message:** Message $number.")
                appendLine()
                appendLine("Candidate outcomes follow.")
            }
            appendLine()
            appendLine("## Planning rules")
            appendLine()
            appendLine("Rules.")
        }
}
