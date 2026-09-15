package org.ergon.cases.domain

import java.time.Instant
import java.util.UUID

/**
 * A durable fact in a case stream, timestamped at the source by [occurredAt].
 * Persistence adds a separate recording time; it must not replace this
 * business timestamp.
 *
 * Deliberately closed: adding a case fact creates compile-time pressure to
 * update exhaustive consumers such as replay, encoding, and projection. The
 * string-based decoding schema must also be updated explicitly because the
 * compiler cannot verify persisted discriminators.
 */
sealed interface CaseEvent {
    val occurredAt: Instant
}

/** Records the case goal and the requester observation that initiated it. */
data class CaseOpened(
    val goal: String,
    val observationId: UUID,
    val observationOriginType: ObservationOriginType,
    val observationProvider: String,
    val observationReference: String?,
    val observationContent: String,
    override val occurredAt: Instant,
) : CaseEvent

/** Records a source observation without assigning semantic meaning to its content. */
data class ObservationRecorded(
    val observationId: UUID,
    val observationOriginType: ObservationOriginType,
    val observationProvider: String,
    val observationReference: String?,
    val observationContent: String,
    override val occurredAt: Instant,
) : CaseEvent

/**
 * Attributes a typed account-access state to one earlier connector observation.
 *
 * [accountReference] must be copied from that observation; accepting it from a
 * binding caller would allow evidence to be reattributed to another account.
 * The case aggregate permits only one account-access fact per observation.
 */
data class AccountAccessStateBound(
    val factId: UUID,
    val observationId: UUID,
    val accountReference: String,
    val state: AccountAccessState,
    override val occurredAt: Instant,
) : CaseEvent

/**
 * Records the exact immutable contract revision chosen for this case.
 *
 * The event stores the identity rather than a mutable alias so later planning
 * and execution can recover the same published meaning. [contractKey] is the
 * canonical lowercase key and [contractRevision] is positive; replay validates
 * both through their domain value types.
 */
data class ResolutionContractRevisionPinned(
    val contractKey: String,
    val contractRevision: Int,
    override val occurredAt: Instant,
) : CaseEvent

/**
 * Closes a case only after one resolution run durably accepts attributable outcome proof.
 *
 * [proofCaseStreamVersion] identifies the final case event assessed as proof;
 * this event must immediately follow that version. The referenced run event
 * and evidence identities retain the decision's audit path.
 */
data class CaseVerifiedResolved(
    val resolutionRunId: UUID,
    val outcomeProofEventId: UUID,
    val proofCaseStreamVersion: Long,
    val factId: UUID,
    val observationId: UUID,
    override val occurredAt: Instant,
) : CaseEvent

/** References the run decision and evidence that permit verified case closure. */
data class VerifiedResolution(
    val resolutionRunId: UUID,
    val outcomeProofEventId: UUID,
    val proofCaseStreamVersion: Long,
    val factId: FactId,
    val observationId: ObservationId,
)
