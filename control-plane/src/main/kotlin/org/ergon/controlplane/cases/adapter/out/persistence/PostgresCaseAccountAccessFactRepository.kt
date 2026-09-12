package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.TenantId
import org.ergon.controlplane.cases.application.AccountAccessFact
import org.ergon.controlplane.cases.application.CaseAccountAccessFactRepository
import org.ergon.controlplane.cases.application.CaseAccountAccessFacts
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** PostgreSQL query adapter for typed account-access facts. */
@Repository
class PostgresCaseAccountAccessFactRepository(
    private val jdbcClient: JdbcClient,
) : CaseAccountAccessFactRepository {
    override fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseAccountAccessFacts? {
        // The left join distinguishes an existing case with no facts from an absent case in one snapshot.
        val rows =
            jdbcClient
                .sql(
                    """
                    SELECT
                        c.case_id,
                        c.stream_version AS case_stream_version,
                        f.fact_id,
                        f.stream_version AS fact_stream_version,
                        f.observation_id,
                        f.account_reference,
                        f.state,
                        f.bound_at,
                        f.recorded_at
                    FROM cases c
                    LEFT JOIN case_account_access_facts f
                        ON f.tenant_id = c.tenant_id
                        AND f.case_id = c.case_id
                    WHERE c.tenant_id = :tenantId
                        AND c.case_id = :caseId
                    ORDER BY f.stream_version
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("caseId", caseId.value)
                .query(DataClassRowMapper(CaseAccountAccessFactRow::class.java))
                .list()
        val first = rows.firstOrNull() ?: return null
        return CaseAccountAccessFacts(
            caseId = first.caseId,
            streamVersion = first.caseStreamVersion,
            facts = rows.mapNotNull(CaseAccountAccessFactRow::toFact),
        )
    }
}

private data class CaseAccountAccessFactRow(
    val caseId: UUID,
    val caseStreamVersion: Long,
    val factId: UUID?,
    val factStreamVersion: Long?,
    val observationId: UUID?,
    val accountReference: String?,
    val state: String?,
    val boundAt: OffsetDateTime?,
    val recordedAt: OffsetDateTime?,
) {
    fun toFact(): AccountAccessFact? {
        if (factId == null) return null
        return AccountAccessFact(
            factId = factId,
            streamVersion = requireNotNull(factStreamVersion),
            observationId = requireNotNull(observationId),
            accountReference = requireNotNull(accountReference),
            state = AccountAccessState.valueOf(requireNotNull(state)),
            boundAt = requireNotNull(boundAt).toInstant(),
            recordedAt = requireNotNull(recordedAt).toInstant(),
        )
    }
}
