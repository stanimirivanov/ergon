package org.ergon.tools.repositorypolicy

/** Actionable repository-policy failure emitted in deterministic source order. */
internal data class Violation(
    val path: String,
    val line: Int,
    val rule: String,
    val message: String,
    val correction: String,
    val policy: String,
) : Comparable<Violation> {
    override fun compareTo(other: Violation): Int =
        compareValuesBy(this, other, Violation::path, Violation::line, Violation::rule, Violation::message)

    override fun toString(): String =
        "$path:$line [$rule] $message\n" +
            "  Correction: $correction\n" +
            "  Policy: $policy"
}
