package org.ergon.controlplane.followup

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.RequesterInformationQuestion
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Shared synthetic question graph; only earlier escalation construction bypasses FKs.
 * Question, answer, ownership and notification integrity always remain enabled.
 */
internal class RequesterInformationDatabaseFixture(
    private val jdbc: JdbcClient,
    private val claims: HumanFollowUpClaimService,
    private val questions: RequesterInformationRequestRepository,
    private val transactions: TransactionTemplate,
    private val now: Instant,
) {
    /** Builds bound requester/owned work/question atomically, joining an existing test transaction. */
    fun question(
        tenant: TenantId = TenantId(UUID.randomUUID()),
        requesterWithoutAuthority: Boolean = false,
    ): Pair<TenantId, RequesterInformationRequest> =
        requireNotNull(
            transactions.execute {
                val graph = RunSupervisionDatabaseFixture.seed(jdbc, now, tenant = tenant)
                val requester = if (requesterWithoutAuthority) registerRequester(tenant) else graph.incoming.value
                val work = UUID.randomUUID()
                // Only earlier escalation construction is synthetic; question/response constraints stay enabled.
                jdbc.sql("SET LOCAL session_replication_role = replica").update()
                jdbc
                    .sql(
                        """
                        INSERT INTO human_follow_up_work_items
                            (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                        VALUES (:tenant, :work, :run, :event, 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :at)
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("work", work)
                    .param("run", graph.run.value)
                    .param("event", UUID.randomUUID())
                    .param("at", now.atOffset(ZoneOffset.UTC))
                    .update()
                jdbc.sql("SET LOCAL session_replication_role = origin").update()
                val caseId =
                    jdbc
                        .sql("SELECT case_id FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run")
                        .param("tenant", tenant.value)
                        .param("run", graph.run.value)
                        .query(UUID::class.java)
                        .single()
                jdbc
                    .sql(
                        """
                        INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
                        VALUES (:tenant, :case, :actor, 'synthetic reviewed requester binding')
                        """.trimIndent(),
                    ).param("tenant", tenant.value)
                    .param("case", caseId)
                    .param("actor", requester)
                    .update()
                val claim = claims.claim(tenant.value, work, graph.outgoing.value).storedClaim.claim
                val request =
                    RequesterInformationRequest(
                        RequesterInformationRequestId(UUID.randomUUID()),
                        HumanFollowUpWorkItemId(work),
                        CaseId(caseId),
                        claim.id,
                        HumanFollowUpOwnershipRevision(1),
                        claim.resolverActorId,
                        RequesterInformationQuestion("Which workspace?"),
                        now,
                    )
                tenant to questions.record(tenant, request).stored.request
            },
        )

    /** Registers identity only; no resolver or approval attestation exists for this actor. */
    private fun registerRequester(tenant: TenantId): UUID {
        val actor = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'workforce-sso', :subject, :at)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("actor", actor)
            .param("subject", "resolver-$actor")
            .param("at", now.atOffset(ZoneOffset.UTC))
            .update()
        return actor
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-26T10:00:00Z")
    }
}
