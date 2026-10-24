package org.ergon.followup.domain

/** Administrative attention metadata; never changes runtime risk, authority, queue order or proof. */
enum class HumanFollowUpPriority { LOW, NORMAL, HIGH, URGENT }

/** Independent per-work position; zero represents no assignment (implicitly NORMAL). */
@JvmInline
value class HumanFollowUpPriorityRevision(
    val value: Long,
) {
    init {
        require(value >= 0) { "priority revision must not be negative" }
    }

    /** @throws IllegalArgumentException when no successor fits in the persisted BIGINT representation. */
    fun next(): HumanFollowUpPriorityRevision {
        require(value < Long.MAX_VALUE) { "priority revision is exhausted" }
        return HumanFollowUpPriorityRevision(value + 1)
    }
}
