package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.resolution.application.AssignedRunCursor
import org.ergon.controlplane.resolution.application.AssignedRunOverview
import org.ergon.controlplane.resolution.application.MAX_ASSIGNED_RUN_PAGE_SIZE
import org.ergon.controlplane.resolution.application.RunSupervisorAssignment
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentConflictException
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.StoredRunSupervisorAssignment
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL boundary for immutable first assignments and assigned-only run discovery. */
@Repository
class PostgresRunSupervisorAssignmentRepository(
    private val jdbcClient: JdbcClient,
) : RunSupervisorAssignmentRepository {
    override fun lockRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): Boolean =
        jdbcClient
            .sql(
                """
                SELECT TRUE
                FROM resolution_runs
                WHERE tenant_id = :tenantId AND run_id = :runId
                FOR UPDATE
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(Boolean::class.java)
            .optional()
            .orElse(false)

    override fun findByCommandId(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunSupervisorAssignment? =
        assignmentQuery("AND assignment.command_id = :commandId")
            .param("tenantId", tenantId.value)
            .param("commandId", commandId)
            .query(DataClassRowMapper(RunSupervisorAssignmentRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredAssignment()

    override fun findByRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredRunSupervisorAssignment? =
        assignmentQuery("AND assignment.run_id = :runId")
            .param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(RunSupervisorAssignmentRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredAssignment()

    override fun findAssigned(
        tenantId: TenantId,
        runId: ResolutionRunId,
        actorId: HumanActorId,
        at: Instant,
    ): StoredRunSupervisorAssignment? =
        assignmentQuery(
            """
            AND assignment.run_id = :runId
            AND assignment.supervisor_actor_id = :actorId
            AND EXISTS (
                SELECT 1
                FROM approval_authority_evidence authority
                WHERE authority.tenant_id = assignment.tenant_id
                    AND authority.actor_id = assignment.supervisor_actor_id
                    AND authority.authority = 'RESOLVER'
                    AND authority.case_id IS NULL
                    AND authority.attested_at <= :at
                    AND authority.expires_at > :at
            )
            """.trimIndent(),
        ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .param("actorId", actorId.value)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .query(DataClassRowMapper(RunSupervisorAssignmentRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredAssignment()

    override fun listAssigned(
        tenantId: TenantId,
        actorId: HumanActorId,
        at: Instant,
        limit: Int,
        after: AssignedRunCursor?,
    ): List<AssignedRunOverview> {
        require(limit in 1..(MAX_ASSIGNED_RUN_PAGE_SIZE + 1)) { "assigned-run row limit is outside the page bound" }
        val cursorPredicate = assignedRunCursorPredicate(after)
        var statement =
            jdbcClient
                .sql(
                    """
                    SELECT
                        assignment.assignment_id,
                        assignment.run_id,
                        run.case_id,
                        state.state,
                        state.version AS state_version,
                        state.updated_at AS state_updated_at,
                        assignment.assigned_at
                    FROM resolution_run_supervisor_assignments assignment
                    JOIN resolution_runs run
                        ON run.tenant_id = assignment.tenant_id
                        AND run.run_id = assignment.run_id
                    JOIN resolution_run_states state
                        ON state.tenant_id = assignment.tenant_id
                        AND state.run_id = assignment.run_id
                    WHERE assignment.tenant_id = :tenantId
                        AND assignment.supervisor_actor_id = :actorId
                        AND state.state IN (
                            'WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION',
                            'VERIFYING', 'ACTION_FAILED'
                        )
                        AND EXISTS (
                            SELECT 1
                            FROM approval_authority_evidence authority
                            WHERE authority.tenant_id = assignment.tenant_id
                                AND authority.actor_id = assignment.supervisor_actor_id
                                AND authority.authority = 'RESOLVER'
                                AND authority.case_id IS NULL
                                AND authority.attested_at <= :at
                                AND authority.expires_at > :at
                        )
                        $cursorPredicate
                    ORDER BY assignment.assigned_at, assignment.assignment_id
                    LIMIT :limit
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("actorId", actorId.value)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
        if (after != null) {
            statement =
                statement
                    .param("afterAssignedAt", after.assignedAt.atOffset(ZoneOffset.UTC))
                    .param("afterAssignmentId", after.assignmentId)
        }
        return statement
            .query(DataClassRowMapper(AssignedRunOverviewRow::class.java))
            .list()
            .map(AssignedRunOverviewRow::toOverview)
    }

    override fun create(
        tenantId: TenantId,
        assignment: RunSupervisorAssignment,
    ): StoredRunSupervisorAssignment {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_run_supervisor_assignments (
                        tenant_id, assignment_id, run_id, supervisor_actor_id,
                        authority_evidence_id, assigning_machine_subject,
                        command_id, assigned_at
                    ) VALUES (
                        :tenantId, :assignmentId, :runId, :actorId,
                        :authorityEvidenceId, :machineSubject,
                        :commandId, :assignedAt
                    )
                    ON CONFLICT DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("assignmentId", assignment.id)
                .param("runId", assignment.runId.value)
                .param("actorId", assignment.supervisorActorId.value)
                .param("authorityEvidenceId", assignment.authorityEvidenceId.value)
                .param("machineSubject", assignment.assigningMachineSubject)
                .param("commandId", assignment.commandId)
                .param("assignedAt", assignment.assignedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: throw RunSupervisorAssignmentConflictException()
        return StoredRunSupervisorAssignment(assignment, recordedAt.toInstant())
    }

    private fun assignmentQuery(extraPredicate: String): JdbcClient.StatementSpec =
        jdbcClient.sql(
            """
            SELECT
                assignment_id,
                run_id,
                supervisor_actor_id,
                authority_evidence_id,
                command_id,
                assigning_machine_subject,
                assigned_at,
                recorded_at
            FROM resolution_run_supervisor_assignments assignment
            WHERE assignment.tenant_id = :tenantId
                $extraPredicate
            """.trimIndent(),
        )

    private fun assignedRunCursorPredicate(after: AssignedRunCursor?): String =
        if (after == null) {
            ""
        } else {
            "AND (assignment.assigned_at, assignment.assignment_id) > (:afterAssignedAt, :afterAssignmentId)"
        }
}

private data class RunSupervisorAssignmentRow(
    val assignmentId: UUID,
    val runId: UUID,
    val supervisorActorId: UUID,
    val authorityEvidenceId: UUID,
    val commandId: UUID,
    val assigningMachineSubject: String,
    val assignedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toStoredAssignment(): StoredRunSupervisorAssignment =
        StoredRunSupervisorAssignment(
            RunSupervisorAssignment(
                id = assignmentId,
                runId = ResolutionRunId(runId),
                supervisorActorId = HumanActorId(supervisorActorId),
                authorityEvidenceId = ApprovalAuthorityEvidenceId(authorityEvidenceId),
                commandId = commandId,
                assigningMachineSubject = assigningMachineSubject,
                assignedAt = assignedAt.toInstant(),
            ),
            recordedAt.toInstant(),
        )
}

private data class AssignedRunOverviewRow(
    val assignmentId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val state: String,
    val stateVersion: Long,
    val stateUpdatedAt: OffsetDateTime,
    val assignedAt: OffsetDateTime,
) {
    fun toOverview(): AssignedRunOverview =
        AssignedRunOverview(
            assignmentId = assignmentId,
            runId = ResolutionRunId(runId),
            caseId = CaseId(caseId),
            state = ResolutionRunState.valueOf(state),
            stateVersion = stateVersion,
            stateUpdatedAt = stateUpdatedAt.toInstant(),
            assignedAt = assignedAt.toInstant(),
        )
}
