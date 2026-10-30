package org.ergon.controlplane.contracts.adapter.out.persistence

import org.ergon.contracts.domain.ContractPromotionAuthority
import org.ergon.controlplane.contracts.application.PromotionAuthorityIntent
import org.ergon.controlplane.contracts.application.PromotionAuthorityRegistration
import org.ergon.controlplane.contracts.application.PromotionAuthorityRepository
import org.ergon.controlplane.contracts.application.PromotionConflict
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/** Trusted internal attestation storage; no caller-supplied timestamp can backdate the entitlement. */
@Repository
class PostgresPromotionAuthorityRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : PromotionAuthorityRepository {
    override fun attest(
        tenant: UUID,
        id: UUID,
        intent: PromotionAuthorityIntent,
    ): PromotionAuthorityRegistration =
        promotionWrite {
            checkNotNull(
                transactions.execute {
                    jdbc
                        .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                        .param("key", "promotion-authority:$tenant:$id")
                        .query { _, _ -> Unit }
                        .single()
                    requireActor(tenant, intent.actorId)
                    val existing = find(tenant, id)
                    if (existing != null) {
                        val recorded =
                            PromotionAuthorityIntent(
                                existing.actorId,
                                existing.sourceProvider,
                                existing.sourceReference,
                                existing.expiresAt,
                            )
                        if (recorded != intent) throw PromotionConflict()
                        PromotionAuthorityRegistration(existing, false)
                    } else {
                        val now =
                            jdbc
                                .sql("SELECT clock_timestamp()")
                                .query(OffsetDateTime::class.java)
                                .single()
                                .toInstant()
                        val valid =
                            ContractPromotionAuthority(
                                id,
                                intent.actorId,
                                intent.sourceProvider,
                                intent.sourceReference,
                                now,
                                intent.expiresAt,
                            )
                        jdbc
                            .sql(
                                """
                                INSERT INTO contract_promotion_authorities
                                    (tenant_id, evidence_id, actor_id, source_provider, source_reference, expires_at)
                                VALUES (:tenant, :id, :actor, :provider, :reference, :expires)
                                """.trimIndent(),
                            ).param("tenant", tenant)
                            .param("id", id)
                            .param("actor", valid.actorId)
                            .param("provider", valid.sourceProvider)
                            .param("reference", valid.sourceReference)
                            .param("expires", OffsetDateTime.ofInstant(valid.expiresAt, java.time.ZoneOffset.UTC))
                            .update()
                        PromotionAuthorityRegistration(checkNotNull(find(tenant, id)), true)
                    }
                },
            )
        }

    private fun requireActor(
        tenant: UUID,
        actor: UUID,
    ) {
        val exists =
            jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM human_actors WHERE tenant_id = :tenant AND actor_id = :actor)")
                .param("tenant", tenant)
                .param("actor", actor)
                .query(Boolean::class.java)
                .single()
        if (!exists) throw PromotionUnavailable()
    }

    override fun find(
        tenant: UUID,
        id: UUID,
    ): ContractPromotionAuthority? =
        jdbc
            .sql("SELECT * FROM contract_promotion_authorities WHERE tenant_id = :tenant AND evidence_id = :id")
            .param("tenant", tenant)
            .param("id", id)
            .query { row, _ -> authority(row) }
            .optional()
            .orElse(null)

    override fun current(
        tenant: UUID,
        actor: UUID,
    ): ContractPromotionAuthority? {
        // Command/contract locks precede this actor lock; revocation cannot race exact retries or fresh admission.
        jdbc
            .sql("SELECT lock_simulation_authority(:tenant, :actor)")
            .param("tenant", tenant)
            .param("actor", actor)
            .query { _, _ -> Unit }
            .single()
        return jdbc
            .sql(
                """
                SELECT a.* FROM contract_promotion_authorities a
                WHERE a.tenant_id = :tenant AND a.actor_id = :actor
                  AND a.attested_at <= clock_timestamp() AND a.expires_at > clock_timestamp()
                  AND NOT EXISTS (SELECT 1 FROM contract_promotion_revocations r
                      WHERE r.tenant_id = a.tenant_id AND r.evidence_id = a.evidence_id)
                ORDER BY a.expires_at DESC, a.evidence_id LIMIT 1
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("actor", actor)
            .query { row, _ -> authority(row) }
            .optional()
            .orElse(null)
    }

    private fun authority(row: ResultSet) =
        ContractPromotionAuthority(
            row.getObject("evidence_id", UUID::class.java),
            row.getObject("actor_id", UUID::class.java),
            row.getString("source_provider"),
            row.getString("source_reference"),
            row.getObject("attested_at", OffsetDateTime::class.java).toInstant(),
            row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
        )
}

/** Only known admission refusals are mapped; unexpected storage errors keep their diagnostics. */
internal fun <T> promotionWrite(block: () -> T): T =
    try {
        block()
    } catch (error: DataAccessException) {
        val states =
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<SQLException>()
                .map { it.sqlState }
                .toSet()
        if ("P7501" in states) throw PromotionUnavailable()
        if ("P7502" in states) throw PromotionConflict()
        require("P7503" !in states) { "invalid promotion authority expiry" }
        throw error
    }
