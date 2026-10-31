package org.ergon.controlplane.evaluation.adapter.out.persistence

import org.ergon.controlplane.evaluation.application.AuthorityRevocation
import org.ergon.controlplane.evaluation.application.AuthorityRevocationIntent
import org.ergon.controlplane.evaluation.application.SimulationAuthorityKind
import org.ergon.controlplane.evaluation.application.SimulationLabAuthority
import org.ergon.controlplane.evaluation.application.SimulationLabConflict
import org.ergon.controlplane.evaluation.application.SimulationLabUnavailable
import org.ergon.controlplane.evaluation.application.ViewerAttestation
import org.ergon.controlplane.evaluation.application.ViewerRegistration
import org.ergon.evaluation.domain.EvaluationViewerAuthority
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class PostgresSimulationLabAuthority(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : SimulationLabAuthority {
    override fun attest(
        tenant: UUID,
        id: UUID,
        intent: ViewerAttestation,
    ): ViewerRegistration =
        checkNotNull(
            transactions.execute {
                jdbc
                    .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "evaluation-viewer:$tenant:$id")
                    .query { _, _ -> Unit }
                    .single()
                val existing = find(tenant, id)
                if (existing != null) {
                    val recorded =
                        ViewerAttestation(
                            existing.actorId,
                            existing.sourceProvider,
                            existing.sourceReference,
                            existing.expiresAt,
                        )
                    if (recorded != intent) throw SimulationLabConflict()
                    ViewerRegistration(existing, false)
                } else {
                    requireActor(tenant, intent.actorId)
                    val at =
                        jdbc
                            .sql("SELECT clock_timestamp()")
                            .query(OffsetDateTime::class.java)
                            .single()
                            .toInstant()
                    val valid =
                        EvaluationViewerAuthority(
                            id,
                            intent.actorId,
                            intent.sourceProvider,
                            intent.sourceReference,
                            at,
                            intent.expiresAt,
                        )
                    jdbc
                        .sql(
                            """
                            INSERT INTO evaluation_viewer_authorities
                                (tenant_id, evidence_id, actor_id, source_provider, source_reference, expires_at)
                            VALUES (:tenant, :id, :actor, :provider, :reference, :expires)
                            """.trimIndent(),
                        ).param("tenant", tenant)
                        .param("id", id)
                        .param("actor", valid.actorId)
                        .param("provider", valid.sourceProvider)
                        .param("reference", valid.sourceReference)
                        .param("expires", OffsetDateTime.ofInstant(valid.expiresAt, ZoneOffset.UTC))
                        .update()
                    ViewerRegistration(checkNotNull(find(tenant, id)), true)
                }
            },
        )

    override fun canView(
        tenant: UUID,
        actor: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (SELECT 1 FROM evaluation_viewer_authorities a
                    WHERE a.tenant_id = :tenant AND a.actor_id = :actor
                      AND a.attested_at <= clock_timestamp() AND a.expires_at > clock_timestamp()
                      AND NOT EXISTS (SELECT 1 FROM evaluation_viewer_revocations r
                          WHERE r.tenant_id = a.tenant_id AND r.evidence_id = a.evidence_id))
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("actor", actor)
            .query(Boolean::class.java)
            .single()

    override fun revoke(
        tenant: UUID,
        kind: SimulationAuthorityKind,
        id: UUID,
        intent: AuthorityRevocationIntent,
    ): AuthorityRevocation =
        checkNotNull(
            transactions.execute {
                val authorityTable = authorityTable(kind)
                val table = revocationTable(kind)
                val actor =
                    jdbc
                        .sql(
                            "SELECT actor_id FROM $authorityTable " +
                                "WHERE tenant_id = :tenant AND evidence_id = :id",
                        ).param("tenant", tenant)
                        .param("id", id)
                        .query(UUID::class.java)
                        .optional()
                        .orElse(null)
                        ?: throw SimulationLabUnavailable()
                jdbc
                    .sql("SELECT lock_simulation_authority(:tenant, :actor)")
                    .param("tenant", tenant)
                    .param("actor", actor)
                    .query { _, _ -> Unit }
                    .single()
                val created =
                    jdbc
                        .sql(
                            """
                            INSERT INTO $table (tenant_id,
                                 evidence_id,
                                 source_provider,
                                 source_reference,
                                 reason)
                            VALUES (:tenant, :id, :provider, :reference, :reason) ON CONFLICT DO NOTHING
                            """.trimIndent(),
                        ).param("tenant", tenant)
                        .param("id", id)
                        .param("provider", intent.sourceProvider)
                        .param("reference", intent.sourceReference)
                        .param("reason", intent.reason)
                        .update() == 1
                val stored = readRevocation(tenant, id, table, created)
                if (stored.intent != intent) throw SimulationLabConflict()
                stored
            },
        )

    private fun readRevocation(
        tenant: UUID,
        id: UUID,
        table: String,
        created: Boolean,
    ): AuthorityRevocation =
        jdbc
            .sql(
                "SELECT source_provider, source_reference, reason, revoked_at FROM $table " +
                    "WHERE tenant_id = :tenant AND evidence_id = :id",
            ).param("tenant", tenant)
            .param("id", id)
            .query { row, _ ->
                AuthorityRevocation(
                    AuthorityRevocationIntent(
                        row.getString("source_provider"),
                        row.getString("source_reference"),
                        row.getString("reason"),
                    ),
                    row.getObject("revoked_at", OffsetDateTime::class.java).toInstant(),
                    created,
                )
            }.single()

    private fun requireActor(
        tenant: UUID,
        actor: UUID,
    ) {
        val available =
            jdbc
                .sql(
                    "SELECT EXISTS (SELECT 1 FROM human_actors WHERE tenant_id = :tenant AND actor_id = :actor)",
                ).param("tenant", tenant)
                .param("actor", actor)
                .query(Boolean::class.java)
                .single()
        if (!available) throw SimulationLabUnavailable()
    }

    private fun find(
        tenant: UUID,
        id: UUID,
    ): EvaluationViewerAuthority? =
        jdbc
            .sql(
                "SELECT * FROM evaluation_viewer_authorities WHERE tenant_id = :tenant AND evidence_id = :id",
            ).param("tenant", tenant)
            .param("id", id)
            .query { row, _ ->
                EvaluationViewerAuthority(
                    id,
                    row.getObject("actor_id", UUID::class.java),
                    row.getString("source_provider"),
                    row.getString("source_reference"),
                    row.getObject("attested_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)
}

private fun authorityTable(kind: SimulationAuthorityKind) =
    when (kind) {
        SimulationAuthorityKind.EVALUATION_VIEWER -> "evaluation_viewer_authorities"
        SimulationAuthorityKind.CONTRACT_PROMOTER -> "contract_promotion_authorities"
    }

private fun revocationTable(kind: SimulationAuthorityKind) =
    when (kind) {
        SimulationAuthorityKind.EVALUATION_VIEWER -> "evaluation_viewer_revocations"
        SimulationAuthorityKind.CONTRACT_PROMOTER -> "contract_promotion_revocations"
    }
