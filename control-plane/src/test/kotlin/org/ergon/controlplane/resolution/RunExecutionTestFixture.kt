package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/** Builds genuine approved source graphs for command and admission-history scenarios. */
internal class RunExecutionTestFixture(
    private val jdbc: JdbcClient,
    private val transactions: TransactionRunner,
    private val requests: ApprovalRequestService,
    private val decisions: ApprovalDecisionService,
    private val at: Instant,
) {
    fun seed(
        outcome: ApprovalDecisionOutcome = ApprovalDecisionOutcome.APPROVED,
        route: Boolean = true,
        connector: String = "identity-stub",
        tenant: TenantId = TenantId(UUID.randomUUID()),
    ): Pair<RunSupervisionDatabaseFixture, ExecuteRunCommand> {
        val fixture = transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, at, tenant = tenant) }
        if (route) {
            jdbc
                .sql(
                    """INSERT INTO tenant_capability_routes (tenant_id, capability, connector)
            VALUES (:tenant, 'identity.account.unlock', :connector) ON CONFLICT DO NOTHING""",
                ).param("tenant", fixture.tenant.value)
                .param("connector", connector)
                .update()
        }
        val request = requests.request(fixture.tenant.value, fixture.run.value).stored.request
        val decision =
            decisions
                .decide(
                    fixture.tenant.value,
                    request.id.value,
                    fixture.outgoing.value,
                    outcome,
                ).decision
        return fixture to
            ExecuteRunCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
                0,
                request.id.value,
                decision.id.value,
            )
    }
}
