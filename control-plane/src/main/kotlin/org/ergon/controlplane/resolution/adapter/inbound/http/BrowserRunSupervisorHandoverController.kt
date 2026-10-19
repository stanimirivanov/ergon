package org.ergon.controlplane.resolution.adapter.inbound.http

import jakarta.servlet.http.HttpSession
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.RunControlFence
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Same-origin handover boundary: session identity and CSRF, never browser self-assignment or execution control. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunSupervisorHandoverController(
    private val actors: AuthenticatedHumanActorResolver,
    private val handovers: RunSupervisorHandoverService,
) {
    /** Lists minimal eligible recipient IDs only after current supervision and expected epoch agree. */
    @GetMapping("/handover-candidates")
    @Suppress("LongParameterList") // Explicit path, session and traversal parameters are the reviewed wire contract.
    fun candidates(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestParam expectedSupervisionRevision: Long,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) afterActorId: UUID?,
    ): ResponseEntity<BrowserRunSupervisorCandidatePage> {
        val actor = actors.resolve(tenantId, principal)
        val page =
            handovers.listCandidates(
                tenantId,
                runId,
                actor.actor.id.value,
                RunSupervisionRevision(expectedSupervisionRevision),
                limit,
                afterActorId,
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            BrowserRunSupervisorCandidatePage(
                page.actorIds.map { BrowserRunSupervisorCandidate(it.value) },
                page.nextActorId,
                page.supervisionRevision.value,
            ),
        )
    }

    /**
     * Applies or exactly replays the authenticated outgoing supervisor's transfer.
     * Spring Security validates the session-bound CSRF header before invocation.
     * The browser chooses a recipient but never supplies the outgoing actor or attestations.
     */
    @PostMapping("/supervisor-handovers")
    fun handover(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody request: BrowserRunSupervisorHandoverRequest,
        session: HttpSession,
    ): ResponseEntity<BrowserRunSupervisorHandoverResponse> {
        val actor = actors.resolve(tenantId, principal)
        val recording =
            handovers.handover(
                HandoverRunSupervisorCommand(
                    tenantId,
                    runId,
                    actor.actor.id.value,
                    request.supervisorActorId,
                    request.commandId,
                    RunSupervisionRevision(request.expectedSupervisionRevision),
                ),
                request.control.forSession(session),
            )
        val fact = recording.stored.handover
        return ResponseEntity
            .status(if (recording.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(
                BrowserRunSupervisorHandoverResponse(
                    fact.commandId,
                    fact.runId.value,
                    fact.revision.value,
                    fact.occurredAt,
                    recording.stored.recordedAt,
                ),
            )
    }
}

/** Opaque registered resolver identity; no provider subject or fabricated display name. */
data class BrowserRunSupervisorCandidate(
    val actorId: UUID,
)

/** Best-effort eligible identities, with an indivisible epoch and private UUID keyset. */
data class BrowserRunSupervisorCandidatePage(
    val entries: List<BrowserRunSupervisorCandidate>,
    val nextActorId: UUID?,
    val supervisionRevision: Long,
)

/** Exact replay intent; no field can grant caller identity or capability authority. */
data class BrowserRunSupervisorHandoverRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val supervisorActorId: UUID,
    val control: RunControlFence? = null,
)

/** Historical transfer acknowledgement, not a claim of current ownership or a private audit payload. */
data class BrowserRunSupervisorHandoverResponse(
    val commandId: UUID,
    val runId: UUID,
    val supervisionRevision: Long,
    val handedOverAt: Instant,
    val recordedAt: Instant,
)
