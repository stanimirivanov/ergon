package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.resolution.application.AssignRunSupervisorCommand
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.RunOutcomeVerification
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** Observes the existing real execution/proof scenario through the protected Console use case. */
internal class OutcomeConsoleObserver(
    private val console: AssignedRunConsoleService,
    private val assignments: RunSupervisorAssignmentService,
    private val jdbc: JdbcClient,
) {
    fun assign(
        tenant: UUID,
        run: UUID,
    ): () -> RunOutcomeVerification {
        val actor = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'workforce-sso', :subject, clock_timestamp())
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("actor", actor)
            .param("subject", "console-$actor")
            .update()
        jdbc
            .sql(
                """
                INSERT INTO approval_authority_evidence (
                    tenant_id, evidence_id, actor_id, authority, case_id, source_provider,
                    source_reference, attested_at, expires_at
                ) VALUES (:tenant, :id, :actor, 'RESOLVER', NULL, 'workforce-sso', 'resolvers',
                    clock_timestamp() - INTERVAL '1 hour', clock_timestamp() + INTERVAL '1 hour')
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("id", UUID.randomUUID())
            .param("actor", actor)
            .update()
        assignments.assign(AssignRunSupervisorCommand(tenant, run, actor, UUID.randomUUID(), "test-dispatcher"))
        assertThatThrownBy { console.get(tenant, run, UUID.randomUUID()) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy { console.get(UUID.randomUUID(), run, actor) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        return { console.get(tenant, run, actor).outcomeVerification }
    }
}
