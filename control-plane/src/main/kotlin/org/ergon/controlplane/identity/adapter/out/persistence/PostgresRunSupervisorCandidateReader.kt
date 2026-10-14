package org.ergon.controlplane.identity.adapter.out.persistence

import org.ergon.controlplane.resolution.application.MAX_RUN_SUPERVISOR_CANDIDATE_PAGE_SIZE
import org.ergon.controlplane.resolution.application.RunSupervisorCandidateReader
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The identity adapter owns eligible resolver discovery; no provider subject or identity label is returned. */
@Repository
class PostgresRunSupervisorCandidateReader(
    private val jdbcClient: JdbcClient,
) : RunSupervisorCandidateReader {
    override fun listCandidates(
        tenantId: TenantId,
        excludedActorId: HumanActorId,
        at: Instant,
        limit: Int,
        afterActorId: UUID?,
    ): List<HumanActorId> {
        require(limit in 1..MAX_CANDIDATE_ROWS) { "candidate row limit is outside the page bound" }
        return jdbcClient
            .sql(
                """
                SELECT actor.actor_id FROM human_actors actor
                WHERE actor.tenant_id = :tenantId AND actor.actor_id <> :excludedActor
                    AND (CAST(:afterActor AS UUID) IS NULL OR actor.actor_id > :afterActor)
                    AND EXISTS (
                        SELECT 1 FROM approval_authority_evidence evidence
                        WHERE evidence.tenant_id = actor.tenant_id AND evidence.actor_id = actor.actor_id
                            AND evidence.authority = 'RESOLVER' AND evidence.case_id IS NULL
                            AND evidence.attested_at <= :at AND evidence.expires_at > :at
                    )
                ORDER BY actor.actor_id ASC LIMIT :limit
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("excludedActor", excludedActorId.value)
            .param("afterActor", afterActorId, java.sql.Types.OTHER)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query(UUID::class.java)
            .list()
            .map { HumanActorId(requireNotNull(it)) }
    }
}

// One lookahead row distinguishes a full final page from a page with a successor.
private const val MAX_CANDIDATE_ROWS = MAX_RUN_SUPERVISOR_CANDIDATE_PAGE_SIZE + 1
