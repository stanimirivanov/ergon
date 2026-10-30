package org.ergon.controlplane.contracts.adapter.out.persistence

import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.controlplane.contracts.application.CurrentContractPromotion
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Shares PR10's contract lock; immutable promotion qualification remains valid until the caller commits. */
@Repository
class PostgresCurrentContractPromotion(
    private val jdbc: JdbcClient,
) : CurrentContractPromotion {
    override fun existsWhileLocked(
        tenant: UUID,
        contract: ResolutionContractIdentity,
    ): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "promotion admission requires a transaction"
        }
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "contract-promotion:$tenant:${contract.key.value}")
            .query { _, _ -> Unit }
            .single()
        return jdbc
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM contract_promotions p
                    WHERE p.tenant_id = :tenant AND p.contract_key = :key AND p.contract_revision = :revision
                      AND p.binding_version = (
                          SELECT MAX(b.version) FROM contract_required_suite_bindings b
                          WHERE b.tenant_id = :tenant AND b.contract_key = :key
                      )
                )
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("key", contract.key.value)
            .param("revision", contract.revision.value)
            .query(Boolean::class.java)
            .single()
    }
}
