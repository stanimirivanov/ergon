package org.ergon.controlplane.knowledge.adapter.out.persistence

import org.ergon.controlplane.knowledge.application.SourceConflict
import org.ergon.controlplane.knowledge.application.SourceRegistration
import org.ergon.controlplane.knowledge.application.SourceRevisionRepository
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

/** INSERT conflict waits for concurrent registration; the following read uses a fresh READ COMMITTED snapshot. */
@Repository
class PostgresSourceRevisionRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : SourceRevisionRepository {
    override fun register(
        source: EvidenceSource,
        actor: HumanActorId,
    ): SourceRegistration =
        checkNotNull(
            transactions.execute {
                val inserted =
                    identity(
                        """
                        INSERT INTO knowledge_source_revisions
                            (tenant_id, source_id, digest, format, content, submitted_by)
                        VALUES (:tenant, :source, :digest, :format, :content, :actor)
                        ON CONFLICT (tenant_id, source_id, digest) DO NOTHING
                        """.trimIndent(),
                        source.revision,
                    ).param("format", source.format.name)
                        .param("content", source.text.toByteArray(Charsets.UTF_8))
                        .param("actor", actor.value)
                        .update() == 1
                val stored = checkNotNull(find(source.revision)) { "inserted source revision missing" }
                if (stored.source != source) throw SourceConflict()
                SourceRegistration(stored, inserted)
            },
        )

    override fun find(revision: SourceRevision): StoredSourceRevision? =
        identity(
            """
            SELECT format, content, submitted_by, recorded_at FROM knowledge_source_revisions
            WHERE tenant_id = :tenant AND source_id = :source AND digest = :digest
            """.trimIndent(),
            revision,
        ).query { row, _ ->
            val source =
                EvidenceSource.fromUtf8(
                    revision.tenantId,
                    revision.sourceId,
                    EvidenceSourceFormat.valueOf(row.getString("format")),
                    row.getBytes("content"),
                )
            check(source.revision == revision) { "stored source digest mismatch" }
            StoredSourceRevision(
                source,
                HumanActorId(row.getObject("submitted_by", UUID::class.java)),
                row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
            )
        }.optional()
            .orElse(null)

    private fun identity(
        sql: String,
        revision: SourceRevision,
    ): JdbcClient.StatementSpec =
        jdbc
            .sql(sql)
            .param("tenant", revision.tenantId.value)
            .param("source", revision.sourceId.value)
            .param("digest", revision.digest.value)
}
