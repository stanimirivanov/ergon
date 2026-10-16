package org.ergon.controlplane.resolution

import org.ergon.controlplane.resolution.adapter.out.connector.FakeIdentityCapabilityConnector
import org.ergon.controlplane.resolution.application.CapabilityConnectorGateway
import org.ergon.controlplane.resolution.application.CapabilityInvocationCommand
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Per-tenant controllable provider; operation keys survive thrown acknowledgements without cross-test state. */
class RunExecutionTestConnector(
    private val jdbc: JdbcClient,
) : CapabilityConnectorGateway {
    private val actions = ConcurrentHashMap<UUID, () -> Unit>()
    private val counts = ConcurrentHashMap<UUID, AtomicInteger>()
    private val operations = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val local = FakeIdentityCapabilityConnector()

    fun onCall(
        tenant: UUID,
        action: () -> Unit,
    ) {
        actions[tenant] = action
    }

    fun calls(tenant: UUID): Int = counts[tenant]?.get() ?: 0

    fun operationCount(tenant: UUID): Int = operations[tenant]?.size ?: 0

    override fun invoke(command: CapabilityInvocationCommand): CapabilityInvocationResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "connector entered a database transaction"
        }
        // A separate autocommit read must already see the committed admission.
        check(
            jdbc
                .sql(
                    """
                    SELECT COUNT(*) FROM resolution_run_execution_commands
                    WHERE tenant_id = :tenant AND authorization_consumption_id = :consumption
                    """.trimIndent(),
                ).param("tenant", command.tenantId.value)
                .param("consumption", command.idempotencyKey)
                .query(Int::class.java)
                .single() == 1,
        ) { "connector ran before reservation/audit commit" }
        counts.computeIfAbsent(command.tenantId.value) { AtomicInteger() }.incrementAndGet()
        operations.computeIfAbsent(command.tenantId.value) { ConcurrentHashMap.newKeySet() }.add(command.idempotencyKey)
        actions[command.tenantId.value]?.invoke()
        return local.invoke(command)
    }
}
