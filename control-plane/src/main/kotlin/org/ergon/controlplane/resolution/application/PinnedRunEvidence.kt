package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

/** Maximum disclosed bindings, in ascending case-stream order; truncation never implies a complete snapshot. */
const val MAX_PINNED_EVIDENCE_FACTS = 100

/**
 * Historical bindings and only their linked sources, not current truth or outcome proof.
 * [caseStreamVersion] is the run's immutable boundary, not the case's latest version.
 * [truncated] means later bindings within that boundary were omitted, not that they do not exist.
 */
data class PinnedRunEvidence(
    val caseId: UUID,
    val caseStreamVersion: Long,
    val facts: List<PinnedAccountAccessFact>,
    val truncated: Boolean,
) {
    init {
        check(caseStreamVersion > 0 && facts.size <= MAX_PINNED_EVIDENCE_FACTS)
        check(!truncated || facts.size == MAX_PINNED_EVIDENCE_FACTS)
        check(facts.zipWithNext().all { (first, next) -> first.streamVersion < next.streamVersion })
        check(facts.all { it.streamVersion <= caseStreamVersion })
        check(facts.map { it.factId }.distinct().size == facts.size)
        check(facts.map { it.source.observationId }.distinct().size == facts.size)
    }
}

/** One stored semantic binding, including the account reference inherited from its exact connector source. */
data class PinnedAccountAccessFact(
    val factId: UUID,
    val streamVersion: Long,
    val accountReference: String,
    val value: AccountAccessState,
    val boundAt: Instant,
    val recordedAt: Instant,
    val source: PinnedEvidenceSource,
) {
    val property: String = "account.access.state"

    init {
        check(source.streamVersion > 0 && source.streamVersion < streamVersion)
        check(source.originType == ObservationOriginType.CONNECTOR && source.reference == accountReference)
    }
}

/**
 * Attributable source text remains untrusted; occurrence and database recording
 * instants have different meanings and must not replace each other.
 */
data class PinnedEvidenceSource(
    val observationId: UUID,
    val streamVersion: Long,
    val originType: ObservationOriginType,
    val provider: String,
    val reference: String?,
    val content: String,
    val observedAt: Instant,
    val recordedAt: Instant,
)

/**
 * Reads only a positive pinned case prefix, after the caller establishes current supervisor/resolver access.
 * Implementations return at most [MAX_PINNED_EVIDENCE_FACTS] bindings and their sources in one database
 * observation. No unbound observations, later bindings or later sources may be disclosed.
 * Absence or a boundary beyond the stored case returns null; inconsistent attribution fails closed.
 */
fun interface PinnedRunEvidenceReader {
    fun find(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): PinnedRunEvidence?
}
