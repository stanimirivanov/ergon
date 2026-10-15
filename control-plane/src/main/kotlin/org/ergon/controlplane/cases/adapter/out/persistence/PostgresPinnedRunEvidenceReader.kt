package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.controlplane.resolution.application.MAX_PINNED_EVIDENCE_FACTS
import org.ergon.controlplane.resolution.application.PinnedAccountAccessFact
import org.ergon.controlplane.resolution.application.PinnedEvidenceSource
import org.ergon.controlplane.resolution.application.PinnedRunEvidence
import org.ergon.controlplane.resolution.application.PinnedRunEvidenceReader
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Case-owned query adapter: one bounded snapshot, without loading unrelated or post-run source text. */
@Repository
class PostgresPinnedRunEvidenceReader(
    private val jdbc: JdbcClient,
) : PinnedRunEvidenceReader {
    override fun find(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): PinnedRunEvidence? {
        require(throughVersion > 0) { "pinned case version must be positive" }
        // The outer case row distinguishes empty evidence from missing/out-of-range history.
        // LIMIT + 1 bounds source material without claiming completeness or reading a mutable latest value.
        val rows =
            jdbc
                .sql(
                    """
                    SELECT f.fact_id, f.stream_version AS fact_version, f.account_reference, f.state,
                        f.bound_at, f.recorded_at AS fact_recorded_at,
                        o.observation_id, o.stream_version AS source_version, o.observation_origin_type,
                        o.observation_provider, o.observation_reference, o.observation_content,
                        o.occurred_at AS observed_at, o.recorded_at AS source_recorded_at
                    FROM cases c
                    LEFT JOIN LATERAL (
                        SELECT fact_id, stream_version, observation_id, account_reference, state, bound_at, recorded_at
                        FROM case_account_access_facts
                        WHERE tenant_id = c.tenant_id AND case_id = c.case_id AND stream_version <= :boundary
                        ORDER BY stream_version
                        LIMIT :rowLimit
                    ) f ON TRUE
                    LEFT JOIN case_timeline_entries o
                        ON o.tenant_id = c.tenant_id AND o.case_id = c.case_id AND o.observation_id = f.observation_id
                    WHERE c.tenant_id = :tenant AND c.case_id = :case AND c.stream_version >= :boundary
                    ORDER BY f.stream_version
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("case", case.value)
                .param("boundary", throughVersion)
                .param("rowLimit", MAX_PINNED_EVIDENCE_FACTS + 1)
                .query(DataClassRowMapper(PinnedEvidenceRow::class.java))
                .list()
        if (rows.isEmpty()) return null
        val facts = rows.mapNotNull(PinnedEvidenceRow::toFact)
        return PinnedRunEvidence(
            case.value,
            throughVersion,
            facts.take(MAX_PINNED_EVIDENCE_FACTS),
            truncated = facts.size > MAX_PINNED_EVIDENCE_FACTS,
        )
    }
}

private data class PinnedEvidenceRow(
    val factId: UUID?,
    val factVersion: Long?,
    val accountReference: String?,
    val state: String?,
    val boundAt: OffsetDateTime?,
    val factRecordedAt: OffsetDateTime?,
    val observationId: UUID?,
    val sourceVersion: Long?,
    val observationOriginType: String?,
    val observationProvider: String?,
    val observationReference: String?,
    val observationContent: String?,
    val observedAt: OffsetDateTime?,
    val sourceRecordedAt: OffsetDateTime?,
) {
    fun toFact(): PinnedAccountAccessFact? {
        if (factId == null) return null
        try {
            return PinnedAccountAccessFact(
                factId,
                checkNotNull(factVersion),
                checkNotNull(accountReference),
                AccountAccessState.valueOf(checkNotNull(state)),
                checkNotNull(boundAt).toInstant(),
                checkNotNull(factRecordedAt).toInstant(),
                PinnedEvidenceSource(
                    checkNotNull(observationId),
                    checkNotNull(sourceVersion),
                    ObservationOriginType.valueOf(checkNotNull(observationOriginType)),
                    checkNotNull(observationProvider),
                    observationReference,
                    checkNotNull(observationContent),
                    checkNotNull(observedAt).toInstant(),
                    checkNotNull(sourceRecordedAt).toInstant(),
                ),
            )
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("stored pinned evidence is invalid", exception)
        }
    }
}
