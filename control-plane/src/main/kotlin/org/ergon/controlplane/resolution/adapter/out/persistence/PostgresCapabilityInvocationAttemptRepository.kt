package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.CapabilityInvocationAttemptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationCompletion
import org.ergon.controlplane.resolution.application.CapabilityInvocationPreparation
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.ZoneOffset
import java.util.UUID

/** Append-only SQL audit; duplicate identities and invalid scope remain integrity failures, never replay. */
@Repository
class PostgresCapabilityInvocationAttemptRepository(
    private val jdbc: JdbcClient,
) : CapabilityInvocationAttemptRepository {
    override fun prepare(
        tenant: TenantId,
        preparation: CapabilityInvocationPreparation,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "invocation audit requires a transaction"
        }
        jdbc
            .sql(
                """
                INSERT INTO capability_invocation_preparations (
                    tenant_id, invocation_attempt_id, authorization_consumption_id, prepared_at
                ) VALUES (:tenant, :attempt, :consumption, :prepared)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("attempt", preparation.id)
            .param("consumption", preparation.consumptionId.value)
            .param("prepared", preparation.preparedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun complete(
        tenant: TenantId,
        attemptId: UUID,
        completion: CapabilityInvocationCompletion,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "invocation audit requires a transaction"
        }
        jdbc
            .sql(
                """
                INSERT INTO capability_invocation_completions (
                    tenant_id, invocation_attempt_id, outcome, invocation_started_at, completed_at, elapsed_nanoseconds
                ) VALUES (:tenant, :attempt, :outcome, :started, :completed, :elapsed)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("attempt", attemptId)
            .param("outcome", completion.outcome.name)
            .param("started", completion.timing.startedAt.atOffset(ZoneOffset.UTC))
            .param("completed", completion.completedAt.atOffset(ZoneOffset.UTC))
            .param("elapsed", completion.timing.elapsedNanoseconds)
            .update()
    }
}
