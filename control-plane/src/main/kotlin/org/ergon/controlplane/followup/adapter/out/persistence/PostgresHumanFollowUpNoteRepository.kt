package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HumanFollowUpNote
import org.ergon.controlplane.followup.application.HumanFollowUpNoteRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpNote
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpNoteId
import org.ergon.followup.domain.HumanFollowUpNoteText
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Uses the caller's ownership-locked transaction; immutable event joins supply attribution. */
@Repository
class PostgresHumanFollowUpNoteRepository(
    private val jdbc: JdbcClient,
) : HumanFollowUpNoteRepository {
    override fun find(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        noteId: HumanFollowUpNoteId,
    ): StoredHumanFollowUpNote? =
        query(tenantId, workItemId, "AND note.note_id = :noteId")
            .param("noteId", noteId.value)
            .query(DataClassRowMapper(NoteRow::class.java))
            .optional()
            .getOrNull()
            ?.toStored()

    override fun latest(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        limit: Int,
    ): List<StoredHumanFollowUpNote> =
        query(tenantId, workItemId, "ORDER BY note.recorded_at DESC, note.note_id DESC LIMIT :limit")
            .param("limit", limit)
            .query(DataClassRowMapper(NoteRow::class.java))
            .list()
            .map(NoteRow::toStored)

    override fun append(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        note: HumanFollowUpNote,
    ): StoredHumanFollowUpNote {
        val recorded =
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_notes (tenant_id, work_item_id, note_id, ownership_revision, body, authored_at)
                    VALUES (:tenantId, :workItemId, :noteId, :revision, :body, :authoredAt)
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("workItemId", workItemId.value)
                .param("noteId", note.id.value)
                .param("revision", note.ownershipRevision.value)
                .param("body", note.text.value)
                .param("authoredAt", note.authoredAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .single()
        return StoredHumanFollowUpNote(note, recorded.toInstant())
    }

    private fun query(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        suffix: String,
    ): JdbcClient.StatementSpec =
        jdbc
            .sql(
                """
                SELECT note.note_id, note.ownership_revision, note.body, note.authored_at, note.recorded_at,
                    owner.claim_id, owner.resolver_actor_id
                FROM human_follow_up_notes note
                JOIN human_follow_up_ownership_events owner
                    ON owner.tenant_id = note.tenant_id AND owner.work_item_id = note.work_item_id
                    AND owner.ownership_revision = note.ownership_revision
                WHERE note.tenant_id = :tenantId AND note.work_item_id = :workItemId
                $suffix
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
}

private data class NoteRow(
    val noteId: UUID,
    val ownershipRevision: Long,
    val body: String,
    val authoredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val claimId: UUID,
    val resolverActorId: UUID,
) {
    fun toStored(): StoredHumanFollowUpNote =
        StoredHumanFollowUpNote(
            HumanFollowUpNote(
                HumanFollowUpNoteId(noteId),
                HumanFollowUpClaimId(claimId),
                HumanFollowUpOwnershipRevision(ownershipRevision),
                HumanActorId(resolverActorId),
                HumanFollowUpNoteText(body),
                authoredAt.toInstant(),
            ),
            recordedAt.toInstant(),
        )
}
