package org.ergon.controlplane.followup.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpNoteId
import org.ergon.followup.domain.HumanFollowUpNoteText
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Immutable author/claim attribution; recording time is assigned only by storage. */
data class HumanFollowUpNote(
    val id: HumanFollowUpNoteId,
    val claimId: HumanFollowUpClaimId,
    val ownershipRevision: HumanFollowUpOwnershipRevision,
    val authorActorId: HumanActorId,
    val text: HumanFollowUpNoteText,
    val authoredAt: Instant,
)

data class StoredHumanFollowUpNote(
    val note: HumanFollowUpNote,
    val recordedAt: Instant,
)

/** Newest-first bounded history; truncated means older notes were omitted. */
data class HumanFollowUpNotesPage(
    val items: List<StoredHumanFollowUpNote>,
    val truncated: Boolean,
)

/**
 * Exact intent for an append, including the claim displayed to the resolver.
 * @throws InvalidHumanFollowUpNoteCommandException for a nonpositive claim revision.
 */
data class HumanFollowUpNoteCommand(
    val tenantId: UUID,
    val workItemId: UUID,
    val actorId: UUID,
    val noteId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val text: String,
) {
    init {
        if (expectedOwnershipRevision <= 0) throw InvalidHumanFollowUpNoteCommandException()
    }
}

data class HumanFollowUpNoteRecording(
    val stored: StoredHumanFollowUpNote,
    val created: Boolean,
)

/** Tenant-scoped append-only storage, called only after the owner gate in a transaction. */
interface HumanFollowUpNoteRepository {
    /** Returns the immutable scoped note or null; callers must establish current access first. */
    fun find(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        noteId: HumanFollowUpNoteId,
    ): StoredHumanFollowUpNote?

    /** Caller holds the work-item lock and has excluded conflicting note identity. */
    fun append(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        note: HumanFollowUpNote,
    ): StoredHumanFollowUpNote

    /** Returns at most [limit] rows in descending (recordedAt, noteId) order. */
    fun latest(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        limit: Int,
    ): List<StoredHumanFollowUpNote>
}

class HumanFollowUpNotesNotFoundException : RuntimeException("owned follow-up notes are unavailable")

class HumanFollowUpNoteConflictException : RuntimeException("note intent conflicts; refresh owned work")

class InvalidHumanFollowUpNoteCommandException : RuntimeException("expectedOwnershipRevision must be positive")

/** Serializes note reads/writes with claim/release; notes never advance ownership or case state. */
class HumanFollowUpNoteService(
    private val claims: HumanFollowUpClaimRepository,
    private val notes: HumanFollowUpNoteRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Appends once or returns the exact original receipt. Current ownership and
     * authority are mandatory even for replay; another owner cannot recover it.
     *
     * @throws HumanFollowUpNotesNotFoundException for every failed current-owner gate.
     * @throws HumanFollowUpNoteConflictException for stale revision/claim or changed intent.
     * @throws org.ergon.followup.domain.InvalidHumanFollowUpNoteTextException for invalid text.
     */
    fun append(command: HumanFollowUpNoteCommand): HumanFollowUpNoteRecording {
        val text = HumanFollowUpNoteText(command.text)
        return transactions.required {
            val tenant = TenantId(command.tenantId)
            val work = HumanFollowUpWorkItemId(command.workItemId)
            val owned = requireOwner(tenant, work, HumanActorId(command.actorId))
            if (
                owned.claim.claim.id.value != command.claimId ||
                owned.ownershipRevision.value != command.expectedOwnershipRevision
            ) {
                throw HumanFollowUpNoteConflictException()
            }
            val noteId = HumanFollowUpNoteId(command.noteId)
            val prior = notes.find(tenant, work, noteId)
            if (prior != null) {
                val sameOwner =
                    prior.note.claimId == owned.claim.claim.id &&
                        prior.note.authorActorId.value == command.actorId &&
                        prior.note.ownershipRevision == owned.ownershipRevision
                if (!sameOwner || prior.note.text != text) {
                    throw HumanFollowUpNoteConflictException()
                }
                HumanFollowUpNoteRecording(prior, created = false)
            } else {
                val note =
                    HumanFollowUpNote(
                        noteId,
                        owned.claim.claim.id,
                        owned.ownershipRevision,
                        owned.claim.claim.resolverActorId,
                        text,
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                    )
                HumanFollowUpNoteRecording(notes.append(tenant, work, note), created = true)
            }
        }
    }

    /**
     * Returns the latest 100 notes, including predecessor authors, only to the
     * current authorized owner. No protected note is read before that gate.
     * @throws HumanFollowUpNotesNotFoundException for absent, closed, unowned or hidden work.
     */
    fun list(
        tenantId: UUID,
        workItemId: UUID,
        actorId: UUID,
    ): HumanFollowUpNotesPage =
        transactions.required {
            val tenant = TenantId(tenantId)
            val work = HumanFollowUpWorkItemId(workItemId)
            requireOwner(tenant, work, HumanActorId(actorId))
            val rows = notes.latest(tenant, work, MAX_NOTES_IN_VIEW + 1)
            HumanFollowUpNotesPage(rows.take(MAX_NOTES_IN_VIEW), rows.size > MAX_NOTES_IN_VIEW)
        }

    private fun requireOwner(
        tenant: TenantId,
        work: HumanFollowUpWorkItemId,
        actor: HumanActorId,
    ): ResolverOwnedHumanFollowUpWork {
        if (!claims.lockOpenWorkItem(tenant, work)) throw HumanFollowUpNotesNotFoundException()
        // Evaluate expiry after any lock wait, not at request arrival.
        return claims.findOwnedWorkForResolver(tenant, work, actor, clock.instant())
            ?: throw HumanFollowUpNotesNotFoundException()
    }
}

private const val MAX_NOTES_IN_VIEW = 100
