package org.ergon.followup.domain

import org.ergon.cases.domain.CaseId
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Instant
import java.util.UUID

/** Stable identity of one durable unit of human follow-up. */
@JvmInline
value class HumanFollowUpWorkItemId(
    val value: UUID,
)

/**
 * Stable routing identity for a human follow-up queue.
 *
 * Keys are lowercase, URL-safe values of at most 63 characters. They are
 * persisted with work rather than inferred later so routing meaning cannot
 * change when configuration evolves.
 */
@JvmInline
value class HumanFollowUpQueueKey private constructor(
    val value: String,
) {
    companion object {
        private val VALID_KEY = Regex("[a-z][a-z0-9-]{0,62}")

        /** Queue for work that needs a resolver to restore an exhausted automated run. */
        val ACCESS_RESTORATION = of("access-restoration")

        /**
         * Creates a queue identity without normalizing its persisted spelling.
         *
         * @throws IllegalArgumentException unless [value] starts with a lowercase
         *   letter and contains at most 63 lowercase letters, digits, or hyphens.
         */
        fun of(value: String): HumanFollowUpQueueKey {
            require(VALID_KEY.matches(value)) {
                "human follow-up queue key must match ${VALID_KEY.pattern}"
            }
            return HumanFollowUpQueueKey(value)
        }
    }
}

/** Current lifecycle state of a human follow-up work item. */
enum class HumanFollowUpWorkItemStatus {
    OPEN,
}

/** Immutable case and escalation coordinates that caused human work to open. */
data class HumanFollowUpSource(
    val caseId: CaseId,
    val runId: ResolutionRunId,
    val escalationEventId: ResolutionRunEventId,
    val reason: ResolutionRunEscalationReason,
)

/** Complete values needed to rehydrate a constrained human follow-up work item. */
data class HumanFollowUpWorkItemSnapshot(
    val id: HumanFollowUpWorkItemId,
    val caseId: CaseId,
    val runId: ResolutionRunId,
    val escalationEventId: ResolutionRunEventId,
    val reason: ResolutionRunEscalationReason,
    val queueKey: HumanFollowUpQueueKey,
    val status: HumanFollowUpWorkItemStatus,
    val openedAt: Instant,
)

/**
 * Durable resolver work created from one explicit exhausted-run escalation.
 *
 * The source case, run, escalation, reason, and initial queue are immutable.
 * Queue routing identifies where work can be discovered; it does not assign,
 * prioritize, or notify a resolver. Those decisions require their own
 * attributable transitions.
 */
@ConsistentCopyVisibility
data class HumanFollowUpWorkItem private constructor(
    val id: HumanFollowUpWorkItemId,
    val caseId: CaseId,
    val runId: ResolutionRunId,
    val escalationEventId: ResolutionRunEventId,
    val reason: ResolutionRunEscalationReason,
    val queueKey: HumanFollowUpQueueKey,
    val status: HumanFollowUpWorkItemStatus,
    val openedAt: Instant,
) {
    companion object {
        /** Opens work for the exact case and escalation that ended automated recovery. */
        fun open(
            id: HumanFollowUpWorkItemId,
            source: HumanFollowUpSource,
            queueKey: HumanFollowUpQueueKey,
            openedAt: Instant,
        ): HumanFollowUpWorkItem =
            HumanFollowUpWorkItem(
                id,
                source.caseId,
                source.runId,
                source.escalationEventId,
                source.reason,
                queueKey,
                HumanFollowUpWorkItemStatus.OPEN,
                openedAt,
            )

        /**
         * Reconstructs a work item read from constrained durable storage.
         *
         * @throws IllegalArgumentException when storage contains an unsupported lifecycle state.
         */
        fun rehydrate(snapshot: HumanFollowUpWorkItemSnapshot): HumanFollowUpWorkItem {
            require(snapshot.status == HumanFollowUpWorkItemStatus.OPEN) {
                "human follow-up work item must be open in this lifecycle version"
            }
            return HumanFollowUpWorkItem(
                snapshot.id,
                snapshot.caseId,
                snapshot.runId,
                snapshot.escalationEventId,
                snapshot.reason,
                snapshot.queueKey,
                snapshot.status,
                snapshot.openedAt,
            )
        }
    }
}
