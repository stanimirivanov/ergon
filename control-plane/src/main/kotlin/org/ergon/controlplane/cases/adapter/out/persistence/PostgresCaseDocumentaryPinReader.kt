package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.application.CaseDocumentaryPinReader
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.DocumentaryBundlePin
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Reads the authoritative event-recording instant and exact pin prefix, never mutable latest membership. */
@Repository
class PostgresCaseDocumentaryPinReader(
    private val jdbc: JdbcClient,
) : CaseDocumentaryPinReader {
    override fun find(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): List<DocumentaryBundlePin> =
        jdbc
            .sql(
                """
                SELECT bundle_id, manifest_sha256, stream_version, recorded_at
                FROM case_documentary_bundle_pins
                WHERE tenant_id = :tenant AND case_id = :case AND stream_version <= :version
                ORDER BY stream_version
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("case", case.value)
            .param("version", throughVersion)
            .query { row, _ ->
                DocumentaryBundlePin(
                    DocumentaryBundleRequirement(
                        row.getObject("bundle_id", UUID::class.java),
                        row.getString("manifest_sha256"),
                    ),
                    row.getLong("stream_version"),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
            .also { check(it.size <= ErgonCase.MAX_DOCUMENTARY_BUNDLES) { "documentary attachment bound is corrupt" } }
}
