package org.ergon.controlplane.resolution

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.BindAccountAccessStateCommand
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.PinResolutionContractCommand
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Real ready-case planning and failure projection; only the prior invocation's source graph is isolated upstream. */
internal class RunRetryTestFixture(
    private val cases: CaseCommandService,
    private val timelines: CaseTimelineRepository,
    private val transactions: TransactionRunner,
    private val jdbc: JdbcClient,
    private val results: ResolutionRunCapabilityResultService,
) {
    fun seed(): RunSupervisionDatabaseFixture {
        val tenant = TenantId(UUID.randomUUID())
        val opened = cases.open(OpenCaseCommand(tenant.value, "Restore account", "PRIVATE_REQUESTER_TEXT"))
        val observed =
            cases.recordConnectorObservation(
                RecordConnectorObservationCommand(
                    tenant.value,
                    opened.caseId,
                    opened.streamVersion,
                    "identity-stub",
                    "account",
                    "PRIVATE_LOCKED_EVIDENCE",
                ),
            )
        val source =
            checkNotNull(timelines.find(tenant, CaseId(opened.caseId)))
                .entries
                .last()
                .observation.id
        val bound =
            cases.bindAccountAccessState(
                BindAccountAccessStateCommand(
                    tenant.value,
                    opened.caseId,
                    observed.streamVersion,
                    source,
                    AccountAccessState.LOCKED,
                ),
            )
        transactions.required { publishSupervisionTestContract(jdbc, tenant) }
        val pinned =
            cases.pinResolutionContract(
                PinResolutionContractCommand(
                    tenant.value,
                    opened.caseId,
                    bound.streamVersion,
                    "restore-workspace-access",
                    1,
                ),
            )
        val fixture =
            transactions.required {
                RunSupervisionDatabaseFixture.seed(jdbc, NOW, caseSnapshot = pinned, tenant = tenant)
            }
        failRun(fixture.tenant, fixture.run)
        return fixture
    }

    fun failRun(
        tenant: TenantId,
        run: ResolutionRunId,
    ) {
        transactions.required {
            // Invocation/grant tests own the upstream reservation graph. Retry
            // writes, assignment triggers and every new FK run normally.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            try {
                jdbc
                    .sql(
                        """
                        INSERT INTO capability_invocation_receipts (
                            tenant_id, authorization_consumption_id, authorization_grant_id, run_id, case_id,
                            policy_revision, step_id, capability, connector, idempotency_key, outcome,
                            provider_operation_reference, consumption_consumed_at, completed_at
                        ) SELECT tenant_id, :consumption, :grant, run_id, case_id,
                            policy_revision, step_id, capability, 'identity-stub', :consumption,
                            'FAILED', :operation, :at, :at
                        FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("run", run.value)
                    .param("consumption", UUID.randomUUID())
                    .param("grant", UUID.randomUUID())
                    .param("operation", "synthetic-operation/${run.value}")
                    .param("at", NOW.atOffset(ZoneOffset.UTC))
                    .update()
            } finally {
                jdbc.sql("SET LOCAL session_replication_role = origin").update()
            }
        }
        results.record(tenant.value, run.value)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-16T20:00:00Z")
    }
}
