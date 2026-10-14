package org.ergon.controlplane.resolution

import org.ergon.controlplane.resolution.adapter.out.persistence.PostgresRunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignment
import org.ergon.controlplane.resolution.application.StoredRunSupervisorAssignment
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Isolated source graph for tests of supervision FKs, gates, and transactions, not a production seed. */
internal data class RunSupervisionDatabaseFixture(
    val tenant: TenantId,
    val run: ResolutionRunId,
    val outgoing: HumanActorId,
    val incoming: HumanActorId,
    val other: HumanActorId,
    val expired: HumanActorId,
    val outgoingEvidence: ApprovalAuthorityEvidenceId,
    val incomingEvidence: ApprovalAuthorityEvidenceId,
    val initial: StoredRunSupervisorAssignment,
) {
    companion object {
        /** Caller must own a transaction so SET LOCAL cannot affect a later pooled borrower. */
        fun seed(
            jdbc: JdbcClient,
            now: Instant,
        ): RunSupervisionDatabaseFixture {
            val tenant = TenantId(UUID.randomUUID())
            val run = ResolutionRunId(UUID.randomUUID())
            val actors = List(4) { HumanActorId(UUID.randomUUID()) }
            val evidence = List(4) { ApprovalAuthorityEvidenceId(UUID.randomUUID()) }
            seedSourceGraph(jdbc, tenant, run, now)
            seedActors(jdbc, tenant, actors, evidence, now)
            val initial =
                PostgresRunSupervisorAssignmentRepository(jdbc).create(
                    tenant,
                    RunSupervisorAssignment(
                        UUID.randomUUID(),
                        run,
                        actors[0],
                        evidence[0],
                        UUID.randomUUID(),
                        "dispatcher",
                        now,
                    ),
                )
            return RunSupervisionDatabaseFixture(
                tenant,
                run,
                actors[0],
                actors[1],
                actors[2],
                actors[3],
                evidence[0],
                evidence[1],
                initial,
            )
        }

        private fun seedSourceGraph(
            jdbc: JdbcClient,
            tenant: TenantId,
            run: ResolutionRunId,
            now: Instant,
        ) {
            // Earlier capabilities own case/contract construction. Only their
            // source-graph FKs are bypassed; every supervision write runs with
            // ordinary constraints and triggers enabled.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            try {
                jdbc
                    .sql(
                        """
                        INSERT INTO resolution_runs (
                            tenant_id, run_id, case_id, case_stream_version, contract_key, contract_revision,
                            policy_revision, step_id, capability, effective_risk, required_approval, initial_state
                        ) VALUES (
                            :tenant, :run, :caseId, 1, 'restore-workspace-access', 1,
                            'ergon.dev/policy/access-restoration/v1', 'unlock-account', 'identity.account.unlock',
                            'HIGH', 'RESOLVER', 'WAITING_FOR_APPROVAL'
                        )
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("run", run.value)
                    .param("caseId", UUID.randomUUID())
                    .update()
                jdbc
                    .sql(
                        """
                        INSERT INTO resolution_run_states (tenant_id, run_id, state, version, updated_at)
                        VALUES (:tenant, :run, 'WAITING_FOR_APPROVAL', 0, :at)
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("run", run.value)
                    .param("at", now.atOffset(ZoneOffset.UTC))
                    .update()
            } finally {
                jdbc.sql("SET LOCAL session_replication_role = origin").update()
            }
        }

        private fun seedActors(
            jdbc: JdbcClient,
            tenant: TenantId,
            actors: List<HumanActorId>,
            evidence: List<ApprovalAuthorityEvidenceId>,
            now: Instant,
        ) {
            actors.forEachIndexed { index, actor ->
                jdbc
                    .sql(
                        """
                        INSERT INTO human_actors (
                            tenant_id, actor_id, identity_provider, identity_subject, registered_at
                        )
                        VALUES (:tenant, :actor, 'workforce-sso', :subject, :at)
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("actor", actor.value)
                    .param("subject", "resolver-${actor.value}")
                    .param("at", now.minusSeconds(300).atOffset(ZoneOffset.UTC))
                    .update()
                jdbc
                    .sql(
                        """
                        INSERT INTO approval_authority_evidence (
                            tenant_id, evidence_id, actor_id, authority, case_id, source_provider, source_reference,
                            attested_at, expires_at
                        ) VALUES (
                            :tenant, :evidence, :actor, 'RESOLVER', NULL, 'workforce-sso', 'resolvers', :at, :expiry
                        )
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("evidence", evidence[index].value)
                    .param("actor", actor.value)
                    .param("at", now.minusSeconds(120).atOffset(ZoneOffset.UTC))
                    .param("expiry", now.plusSeconds(if (index == 3) -60 else 3600).atOffset(ZoneOffset.UTC))
                    .update()
            }
        }
    }
}
