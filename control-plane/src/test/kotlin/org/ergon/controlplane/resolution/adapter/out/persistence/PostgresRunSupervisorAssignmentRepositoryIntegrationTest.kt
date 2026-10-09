package org.ergon.controlplane.resolution.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.AssignRunSupervisorCommand
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunCursor
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignment
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentConflictException
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentIdentityGenerator
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class PostgresRunSupervisorAssignmentRepositoryIntegrationTest(
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val runs: ResolutionRunRepository,
    @Autowired private val transitions: ResolutionRunTransitionRepository,
    @Autowired private val transactionRunner: TransactionRunner,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `machine assignment replays the durable first intent and rejects competing intent`() {
        val tenant = TenantId(UUID.randomUUID())
        val run = ResolutionRunId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        val evidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        seedRunAndAuthority(tenant, run, actor, evidenceId)
        val command =
            AssignRunSupervisorCommand(
                tenant.value,
                run.value,
                actor.value,
                UUID.randomUUID(),
                "resolution-router",
            )

        val first = assignmentService(NOW).assign(command)
        val replay = assignmentService(NOW.plusSeconds(61)).assign(command)

        assertThat(first.created).isTrue()
        assertThat(replay.created).isFalse()
        assertThat(replay.stored).isEqualTo(first.stored)
        assertThat(assignments.findByRun(tenant, run)).isEqualTo(first.stored)
        assertThatThrownBy { assignmentService(NOW).assign(command.copy(commandId = UUID.randomUUID())) }
            .isInstanceOf(RunSupervisorAssignmentConflictException::class.java)
        assertThatThrownBy {
            assignmentService(NOW).assign(command.copy(supervisorActorId = UUID.randomUUID()))
        }.isInstanceOf(RunSupervisorAssignmentConflictException::class.java)
    }

    @Test
    fun `assigned browser reads require the exact assignee and current resolver evidence`() {
        val tenant = TenantId(UUID.randomUUID())
        val run = ResolutionRunId(UUID.randomUUID())
        val otherRun = ResolutionRunId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        val otherActor = HumanActorId(UUID.randomUUID())
        val evidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        seedRunAndAuthority(tenant, run, actor, evidenceId)
        seedRun(tenant, otherRun)
        seedActorAndAuthority(tenant, otherActor, ApprovalAuthorityEvidenceId(UUID.randomUUID()))
        assignmentService(NOW).assign(
            AssignRunSupervisorCommand(tenant.value, run.value, actor.value, UUID.randomUUID(), "resolution-router"),
        )

        val console = consoleService(NOW)
        assertThat(console.list(tenant.value, actor.value, 30, null).entries.map { it.runId })
            .containsExactly(run)
        assertThat(
            console
                .get(tenant.value, run.value, actor.value)
                .start.run.id,
        ).isEqualTo(run)
        assertThat(console.list(tenant.value, otherActor.value, 30, null).entries).isEmpty()
        assertThatThrownBy { console.get(tenant.value, run.value, otherActor.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy { console.get(tenant.value, otherRun.value, actor.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy { console.get(UUID.randomUUID(), run.value, actor.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)

        jdbcClient
            .sql(
                """
                UPDATE resolution_run_states
                SET state = 'VERIFIED_RESOLVED', version = 2
                WHERE tenant_id = :tenantId AND run_id = :runId
                """.trimIndent(),
            ).param("tenantId", tenant.value)
            .param("runId", run.value)
            .update()
        assertThat(console.list(tenant.value, actor.value, 30, null).entries).isEmpty()
        assertThat(console.get(tenant.value, run.value, actor.value).state.state)
            .isEqualTo(ResolutionRunState.VERIFIED_RESOLVED)

        val afterExpiry = consoleService(NOW.plusSeconds(61))
        assertThat(afterExpiry.list(tenant.value, actor.value, 30, null).entries).isEmpty()
        assertThatThrownBy { afterExpiry.get(tenant.value, run.value, actor.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    private fun assignmentService(at: Instant) =
        RunSupervisorAssignmentService(
            assignments,
            authorities,
            RunSupervisorAssignmentIdentityGenerator { UUID.randomUUID() },
            transactionRunner,
            Clock.fixed(at, ZoneOffset.UTC),
        )

    private fun consoleService(at: Instant) =
        AssignedRunConsoleService(
            assignments,
            authorities,
            runs,
            transitions,
            transactionRunner,
            Clock.fixed(at, ZoneOffset.UTC),
        )

    @Test
    fun `assigned reads require exact actor tenant and current resolver evidence`() {
        val tenant = TenantId(UUID.randomUUID())
        val run = ResolutionRunId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        val otherActor = HumanActorId(UUID.randomUUID())
        val evidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        seedRunAndAuthority(tenant, run, actor, evidenceId)
        seedActorAndAuthority(tenant, otherActor, ApprovalAuthorityEvidenceId(UUID.randomUUID()))
        val assignment = assignment(run, actor, evidenceId, NOW)

        assertThat(assignments.lockRun(tenant, run)).isTrue()
        val stored = assignments.create(tenant, assignment)

        assertThat(assignments.findByCommandId(tenant, assignment.commandId)).isEqualTo(stored)
        assertThat(assignments.findByRun(tenant, run)).isEqualTo(stored)
        assertThat(assignments.findAssigned(tenant, run, actor, NOW)).isEqualTo(stored)
        assertThat(assignments.findAssigned(tenant, run, otherActor, NOW)).isNull()
        assertThat(assignments.findAssigned(tenant, run, actor, NOW.plusSeconds(61))).isNull()
        assertThat(assignments.findAssigned(TenantId(UUID.randomUUID()), run, actor, NOW)).isNull()
        assertThat(assignments.lockRun(TenantId(UUID.randomUUID()), run)).isFalse()
    }

    @Test
    fun `assigned discovery is active only and uses stable assignment keyset`() {
        val tenant = TenantId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        val evidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val firstRun = ResolutionRunId(UUID.randomUUID())
        val secondRun = ResolutionRunId(UUID.randomUUID())
        seedRunAndAuthority(tenant, firstRun, actor, evidenceId)
        seedRun(tenant, secondRun)
        val first = assignment(firstRun, actor, evidenceId, NOW)
        val second = assignment(secondRun, actor, evidenceId, NOW.plusSeconds(1))
        assignments.create(tenant, first)
        assignments.create(tenant, second)

        val firstPage = assignments.listAssigned(tenant, actor, NOW, 1, null)
        val secondPage =
            assignments.listAssigned(
                tenant,
                actor,
                NOW,
                1,
                AssignedRunCursor(firstPage.single().assignedAt, firstPage.single().assignmentId),
            )

        assertThat(firstPage.map { it.runId }).containsExactly(firstRun)
        assertThat(firstPage.single().state).isEqualTo(ResolutionRunState.WAITING_FOR_APPROVAL)
        assertThat(secondPage.map { it.runId }).containsExactly(secondRun)
        assertThat(assignments.listAssigned(tenant, actor, NOW.plusSeconds(61), 10, null)).isEmpty()

        jdbcClient
            .sql(
                """
                UPDATE resolution_run_states
                SET state = 'VERIFIED_RESOLVED', version = 2
                WHERE tenant_id = :tenantId AND run_id = :runId
                """.trimIndent(),
            ).param("tenantId", tenant.value)
            .param("runId", firstRun.value)
            .update()
        assertThat(assignments.listAssigned(tenant, actor, NOW, 10, null).map { it.runId })
            .containsExactly(secondRun)
        assertThat(assignments.findAssigned(tenant, firstRun, actor, NOW)).isNotNull()
    }

    @Test
    fun `run and command uniqueness reject conflicting first assignments`() {
        val tenant = TenantId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        val evidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val firstRun = ResolutionRunId(UUID.randomUUID())
        val secondRun = ResolutionRunId(UUID.randomUUID())
        seedRunAndAuthority(tenant, firstRun, actor, evidenceId)
        seedRun(tenant, secondRun)
        val first = assignment(firstRun, actor, evidenceId, NOW)
        assignments.create(tenant, first)

        assertThatThrownBy { assignments.create(tenant, assignment(firstRun, actor, evidenceId, NOW)) }
            .isInstanceOf(RunSupervisorAssignmentConflictException::class.java)
        assertThatThrownBy {
            assignments.create(
                tenant,
                assignment(secondRun, actor, evidenceId, NOW.plusSeconds(1)).copy(commandId = first.commandId),
            )
        }.isInstanceOf(RunSupervisorAssignmentConflictException::class.java)
    }

    @Test
    fun `recorded supervisor assignments cannot be rewritten`() {
        val tenant = TenantId(UUID.randomUUID())
        val run = ResolutionRunId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        val evidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        seedRunAndAuthority(tenant, run, actor, evidenceId)
        assignments.create(tenant, assignment(run, actor, evidenceId, NOW))

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_run_supervisor_assignments
                    SET assigning_machine_subject = 'changed-machine'
                    WHERE tenant_id = :tenantId AND run_id = :runId
                    """.trimIndent(),
                ).param("tenantId", tenant.value)
                .param("runId", run.value)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `upgrade preserves existing unassigned runs without inventing supervisors`() {
        val schema = "supervision_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        flyway(schema).target(PREVIOUS_VERSION).load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.execute("SET session_replication_role = replica")
                statement.executeUpdate(
                    """
                    INSERT INTO resolution_runs (
                        tenant_id, run_id, case_id, case_stream_version,
                        contract_key, contract_revision, policy_revision,
                        step_id, capability, effective_risk, required_approval,
                        initial_state
                    ) VALUES (
                        '11111111-1111-1111-1111-111111111111',
                        '22222222-2222-2222-2222-222222222222',
                        '33333333-3333-3333-3333-333333333333', 1,
                        'restore-workspace-access', 1, 'ergon.dev/policy/access-restoration/v1',
                        'unlock-account', 'identity.account.unlock', 'HIGH', 'RESOLVER',
                        'WAITING_FOR_APPROVAL'
                    )
                    """.trimIndent(),
                )
                statement.execute("SET session_replication_role = origin")
            }
        }

        flyway(schema).load().migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM resolution_runs").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(1)
                }
                statement.executeQuery("SELECT COUNT(*) FROM resolution_run_supervisor_assignments").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isZero()
                }
            }
        }
    }

    private fun flyway(schema: String) =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .defaultSchema(schema)
            .schemas(schema)
            .table("ergon_flyway_schema_history")
            .locations("classpath:db/migration")

    private fun seedRunAndAuthority(
        tenant: TenantId,
        run: ResolutionRunId,
        actor: HumanActorId,
        evidenceId: ApprovalAuthorityEvidenceId,
    ) {
        seedRun(tenant, run)
        seedActorAndAuthority(tenant, actor, evidenceId)
    }

    private fun seedRun(
        tenant: TenantId,
        run: ResolutionRunId,
    ) {
        // Earlier migrations own the source graph; this fixture isolates the
        // new assignment foreign keys and assigned-read query.
        jdbcClient.sql("SET LOCAL session_replication_role = replica").update()
        try {
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_runs (
                        tenant_id, run_id, case_id, case_stream_version,
                        contract_key, contract_revision, policy_revision,
                        step_id, capability, effective_risk, required_approval,
                        initial_state
                    ) VALUES (
                        :tenantId, :runId, :caseId, 1,
                        'restore-workspace-access', 1, 'ergon.dev/policy/access-restoration/v1',
                        'unlock-account', 'identity.account.unlock', 'HIGH', 'RESOLVER',
                        'WAITING_FOR_APPROVAL'
                    )
                    """.trimIndent(),
                ).param("tenantId", tenant.value)
                .param("runId", run.value)
                .param("caseId", UUID.randomUUID())
                .update()
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_run_states (tenant_id, run_id, state, version, updated_at)
                    VALUES (:tenantId, :runId, 'WAITING_FOR_APPROVAL', 0, :updatedAt)
                    """.trimIndent(),
                ).param("tenantId", tenant.value)
                .param("runId", run.value)
                .param("updatedAt", NOW.atOffset(ZoneOffset.UTC))
                .update()
        } finally {
            jdbcClient.sql("SET LOCAL session_replication_role = origin").update()
        }
    }

    private fun seedActorAndAuthority(
        tenant: TenantId,
        actor: HumanActorId,
        evidenceId: ApprovalAuthorityEvidenceId,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO human_actors (
                    tenant_id, actor_id, identity_provider, identity_subject, registered_at
                ) VALUES (
                    :tenantId, :actorId, 'workforce-sso', :subject, :registeredAt
                )
                """.trimIndent(),
            ).param("tenantId", tenant.value)
            .param("actorId", actor.value)
            .param("subject", "employee-${actor.value}")
            .param("registeredAt", NOW.minusSeconds(120).atOffset(ZoneOffset.UTC))
            .update()
        jdbcClient
            .sql(
                """
                INSERT INTO approval_authority_evidence (
                    tenant_id, evidence_id, actor_id, authority, case_id,
                    source_provider, source_reference, attested_at, expires_at
                ) VALUES (
                    :tenantId, :evidenceId, :actorId, 'RESOLVER', NULL,
                    'workforce-sso', 'groups/resolvers', :attestedAt, :expiresAt
                )
                """.trimIndent(),
            ).param("tenantId", tenant.value)
            .param("evidenceId", evidenceId.value)
            .param("actorId", actor.value)
            .param("attestedAt", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))
            .param("expiresAt", NOW.plusSeconds(60).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun assignment(
        run: ResolutionRunId,
        actor: HumanActorId,
        evidenceId: ApprovalAuthorityEvidenceId,
        at: Instant,
    ) = RunSupervisorAssignment(
        id = UUID.randomUUID(),
        runId = run,
        supervisorActorId = actor,
        authorityEvidenceId = evidenceId,
        commandId = UUID.randomUUID(),
        assigningMachineSubject = "resolution-router",
        assignedAt = at,
    )

    companion object {
        private const val PREVIOUS_VERSION = "20261002043900"
        private val NOW = Instant.parse("2026-10-09T04:00:00Z")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
