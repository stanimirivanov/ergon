package org.ergon.tools.repositorypolicy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MilestonePolicyTest {
    @Test
    fun `nine published milestones and their boundaries agree`() {
        assertThat(MilestonePolicy.check(repository(roadmap()))).isEmpty()
    }

    @Test
    fun `deleting latest milestone is detected by retained high-water mark`() {
        val violations = MilestonePolicy.check(repository(roadmap(latest = 8)))

        assertThat(violations.map { it.rule }).containsExactly("milestone.sequence")
        assertThat(violations.single().message).contains("M08", "M09")
    }

    @Test
    fun `removing only the M09 index row retains an actionable stale boundary failure`() {
        val roadmap = roadmap().replace("| M09 - Outcome 9 | Message 9. |\n", "")

        assertThat(MilestonePolicy.check(repository(roadmap)).map { it.rule })
            .containsExactly("milestone.sequence", "milestone.boundaries")
    }

    @Test
    fun `removing only the M09 boundary cannot pass with a complete index`() {
        val roadmap =
            roadmap().replace(
                "## M09 - Outcome 9\n\n**Message:** Message 9.\n\nCandidate outcomes follow.\n",
                "",
            )

        assertThat(MilestonePolicy.check(repository(roadmap)).map { it.rule })
            .containsExactly("milestone.boundaries")
    }

    @Test
    fun `M09 message must match and cannot hide in HTML`() {
        val mismatched = roadmap().replace("**Message:** Message 9.", "**Message:** Another outcome.")
        val hidden = roadmap().replace("| M09 - Outcome 9 | Message 9. |", "| M09 - Outcome 9 | <!-- Message 9. --> |")

        assertThat(MilestonePolicy.check(repository(mismatched)).map { it.rule }).contains("milestone.message")
        assertThat(MilestonePolicy.check(repository(hidden)).map { it.rule }).contains("milestone.message")
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

    // Keep the published fixture independent of the policy constant: lowering the
    // checker high-water mark must fail the positive test, not shrink its input.
    private fun roadmap(latest: Int = 9): String =
        buildString {
            appendLine("# Milestones")
            appendLine()
            appendLine("## Milestone index")
            appendLine()
            appendLine("| Milestone title | Short message |")
            appendLine("|:--|:--|")
            (1..latest).forEach { number ->
                val padded = number.toString().padStart(2, '0')
                appendLine("| M$padded - Outcome $number | Message $number. |")
            }
            (1..latest).forEach { number ->
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
