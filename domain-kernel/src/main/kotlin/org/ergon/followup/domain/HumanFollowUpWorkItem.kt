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
    val status: HumanFollowUpWorkItemStatus,
    val openedAt: Instant,
)

/**
 * Durable resolver work created from one explicit exhausted-run escalation.
 *
 * The source case, run, escalation, and reason are immutable. This first
 * lifecycle slice opens work without assigning, prioritizing, or notifying a
 * resolver; those decisions require their own attributable transitions.
 */
@ConsistentCopyVisibility
data class HumanFollowUpWorkItem private constructor(
    val id: HumanFollowUpWorkItemId,
    val caseId: CaseId,
    val runId: ResolutionRunId,
    val escalationEventId: ResolutionRunEventId,
    val reason: ResolutionRunEscalationReason,
    val status: HumanFollowUpWorkItemStatus,
    val openedAt: Instant,
) {
    companion object {
        /** Opens work for the exact case and escalation that ended automated recovery. */
        fun open(
            id: HumanFollowUpWorkItemId,
            source: HumanFollowUpSource,
            openedAt: Instant,
        ): HumanFollowUpWorkItem =
            HumanFollowUpWorkItem(
                id,
                source.caseId,
                source.runId,
                source.escalationEventId,
                source.reason,
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
                snapshot.status,
                snapshot.openedAt,
            )
        }
    }
}
