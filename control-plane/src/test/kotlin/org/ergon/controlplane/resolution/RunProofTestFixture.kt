package org.ergon.controlplane.resolution

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.BindAccountAccessStateCommand
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.CaseWriteResult
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.FinalizeRunProofCommand
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Real case/event/transition construction with an isolated upstream connector
 * receipt fixture. Only the pre-existing run/consumption source graph bypasses
 * FKs; proof, closure, supervision and command writes use ordinary constraints.
 */
internal class RunProofTestFixture(
    private val cases: CaseCommandService,
    private val timelines: CaseTimelineRepository,
    private val transactions: TransactionRunner,
    private val jdbc: JdbcClient,
    private val results: ResolutionRunCapabilityResultService,
    private val console: AssignedRunConsoleService,
) {
    fun seed(): RunSupervisionDatabaseFixture {
        val tenant = TenantId(UUID.randomUUID())
        val opened = cases.open(OpenCaseCommand(tenant.value, "Restore account", "Synthetic requester observation"))
        val fixture =
            transactions.required {
                RunSupervisionDatabaseFixture.seed(jdbc, NOW, caseSnapshot = opened, tenant = tenant).also {
                    seedReceipt(it, opened)
                }
            }
        results.record(tenant.value, fixture.run.value)
        append(fixture, AccountAccessState.ACTIVE)
        return fixture
    }

    fun command(fixture: RunSupervisionDatabaseFixture): FinalizeRunProofCommand {
        val detail = console.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        val proof = detail.outcomeVerification
        val evidence = checkNotNull(proof.evidence)
        return FinalizeRunProofCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            detail.supervisionRevision,
            detail.state.version,
            checkNotNull(proof.caseStreamVersion),
            evidence.fact.factId.value,
            evidence.fact.observationId.value,
        )
    }

    fun append(
        fixture: RunSupervisionDatabaseFixture,
        state: AccountAccessState,
    ) {
        val caseId =
            console
                .get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                .start.run.caseId
        val current = checkNotNull(timelines.find(fixture.tenant, caseId))
        val observed =
            cases.recordConnectorObservation(
                RecordConnectorObservationCommand(
                    fixture.tenant.value,
                    caseId.value,
                    current.streamVersion,
                    "synthetic-identity",
                    "synthetic-account",
                    "Account is $state",
                ),
            )
        val source =
            checkNotNull(timelines.find(fixture.tenant, CaseId(observed.caseId)))
                .entries
                .last()
                .observation.id
        cases.bindAccountAccessState(
            BindAccountAccessStateCommand(fixture.tenant.value, caseId.value, observed.streamVersion, source, state),
        )
    }

    private fun seedReceipt(
        fixture: RunSupervisionDatabaseFixture,
        case: CaseWriteResult,
    ) {
        jdbc.sql("SET LOCAL session_replication_role = replica").update()
        try {
            jdbc
                .sql(
                    """
                    INSERT INTO capability_invocation_receipts (
                        tenant_id, authorization_consumption_id, authorization_grant_id, run_id, case_id,
                        policy_revision, step_id, capability, connector, idempotency_key, outcome,
                        provider_operation_reference, consumption_consumed_at, completed_at
                    ) VALUES (:tenant, :consumption, :grant, :run, :case, 'ergon.dev/policy/access-restoration/v1',
                        'unlock-account', 'identity.account.unlock', 'synthetic-identity', :consumption,
                        'SUCCEEDED', 'synthetic-operation', :at, :at)
                    """.trimIndent(),
                ).param("tenant", fixture.tenant.value)
                .param("run", fixture.run.value)
                .param("case", case.caseId)
                .param("consumption", UUID.randomUUID())
                .param("grant", UUID.randomUUID())
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update()
        } finally {
            jdbc.sql("SET LOCAL session_replication_role = origin").update()
        }
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-15T08:00:00Z")
    }
}
