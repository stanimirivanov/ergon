package org.ergon.followup.domain

/** Administrative attention metadata; never changes runtime risk, authority, queue order or proof. */
enum class HumanFollowUpPriority { LOW, NORMAL, HIGH, URGENT }

/**
 * Value and independent position read together; zero means the implicit NORMAL default.
 * This is metadata at a query snapshot, never an ownership or authorization grant.
 * @throws IllegalArgumentException if a non-NORMAL value is paired with revision zero.
 */
data class HumanFollowUpPrioritySnapshot(
    val priority: HumanFollowUpPriority = HumanFollowUpPriority.NORMAL,
    val revision: HumanFollowUpPriorityRevision = HumanFollowUpPriorityRevision(0),
) {
    init {
        require(revision.value != 0L || priority == HumanFollowUpPriority.NORMAL) {
            "priority revision zero must mean NORMAL"
        }
    }
}

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
