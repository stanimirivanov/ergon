package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpNoteCommand
import org.ergon.controlplane.followup.application.HumanFollowUpNoteConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpNoteService
import org.ergon.controlplane.followup.application.HumanFollowUpNotesNotFoundException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpNoteCommandException
import org.ergon.controlplane.followup.application.StoredHumanFollowUpNote
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.followup.domain.InvalidHumanFollowUpNoteTextException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Session actor owns authorship; existing BFF filters enforce CSRF and no-store. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/notes")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserHumanFollowUpNoteController(
    private val actors: AuthenticatedHumanActorResolver,
    private val notes: HumanFollowUpNoteService,
) {
    /** Current-owner history; predecessor authors remain attributable without provider identities. */
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): BrowserHumanFollowUpNotesPage {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val page = notes.list(tenantId, workItemId, actor)
        return BrowserHumanFollowUpNotesPage(page.items.map { it.toBrowserResponse() }, page.truncated)
    }

    /** Appends exact text once; retry the same note ID/body/claim/revision after uncertainty. */
    @PostMapping
    fun append(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @RequestBody request: BrowserHumanFollowUpNoteRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserHumanFollowUpNoteResponse> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val recording =
            notes.append(
                HumanFollowUpNoteCommand(
                    tenantId,
                    workItemId,
                    actor,
                    request.noteId,
                    request.claimId,
                    request.expectedOwnershipRevision,
                    request.text,
                ),
            )
        return ResponseEntity
            .status(
                if (recording.created) HttpStatus.CREATED else HttpStatus.OK,
            ).body(recording.stored.toBrowserResponse())
    }

    @ExceptionHandler(
        InvalidHumanFollowUpNoteTextException::class,
        InvalidHumanFollowUpNoteCommandException::class,
        HumanFollowUpNotesNotFoundException::class,
        HumanFollowUpNoteConflictException::class,
    )
    fun rejected(exception: RuntimeException): ProblemDetail {
        val (status, type) =
            when (exception) {
                is HumanFollowUpNotesNotFoundException -> HttpStatus.NOT_FOUND to "human-follow-up-notes-not-found"
                is HumanFollowUpNoteConflictException -> HttpStatus.CONFLICT to "human-follow-up-note-conflict"
                else -> HttpStatus.BAD_REQUEST to "invalid-human-follow-up-note"
            }
        return ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
        }
    }
}

/** Actor identity is intentionally absent: the verified session supplies it. */
data class BrowserHumanFollowUpNoteRequest(
    val noteId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val text: String,
)

/** Internal author identity is disclosed only within current owned work, never provider identity. */
data class BrowserHumanFollowUpNoteResponse(
    val noteId: UUID,
    val claimId: UUID,
    val ownershipRevision: Long,
    val authorActorId: UUID,
    val text: String,
    val authoredAt: Instant,
    val recordedAt: Instant,
)

data class BrowserHumanFollowUpNotesPage(
    val items: List<BrowserHumanFollowUpNoteResponse>,
    val truncated: Boolean,
)

private fun StoredHumanFollowUpNote.toBrowserResponse(): BrowserHumanFollowUpNoteResponse =
    BrowserHumanFollowUpNoteResponse(
        note.id.value,
        note.claimId.value,
        note.ownershipRevision.value,
        note.authorActorId.value,
        note.text.value,
        note.authoredAt,
        recordedAt,
    )
