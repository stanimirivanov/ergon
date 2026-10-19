package org.ergon.controlplane.resolution.adapter.inbound.http

import jakarta.servlet.http.HttpSession
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.RunControlAction
import org.ergon.controlplane.resolution.application.RunControlCommand
import org.ergon.controlplane.resolution.application.RunControlFence
import org.ergon.controlplane.resolution.application.RunControlService
import org.ergon.controlplane.resolution.application.RunControlView
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Instant
import java.util.UUID

/** Existing authenticated session and CSRF boundary; lease receipts never disclose server session bindings. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunControlController(
    private val actors: AuthenticatedHumanActorResolver,
    private val controls: RunControlService,
) {
    /** Current-supervisor-only observation; neither reading nor countdown display renews control. */
    @GetMapping("/control")
    fun status(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        session: HttpSession,
    ): ResponseEntity<RunControlView> =
        ResponseEntity
            .ok()
            .cacheControl(
                CacheControl.noStore(),
            ).body(
                controls.status(
                    tenantId,
                    runId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    session.runControlHolder(),
                ),
            )

    /** Exact historical replay acknowledges the original expiry, not reacquisition or extension. */
    @PostMapping("/control-commands")
    fun change(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunControlRequest,
        session: HttpSession,
    ): ResponseEntity<BrowserRunControlReceipt> {
        val result =
            controls.change(
                RunControlCommand(
                    tenantId,
                    runId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    body.commandId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.action,
                    body.control,
                ),
                session.runControlHolder(),
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            BrowserRunControlReceipt(
                result.command.commandId,
                runId,
                result.command.action,
                result.command.supervisionRevision.value,
                result.fence,
                result.changedAt,
                result.expiresAt,
                result.recordedAt,
            ),
        )
    }

    @ExceptionHandler(AssignedRunNotFoundException::class)
    fun missing() =
        runControlProblem(
            HttpStatus.NOT_FOUND,
            "assigned-resolution-run-not-found",
            "Assigned run unavailable",
        )

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid() = runControlProblem(HttpStatus.BAD_REQUEST, "invalid-run-control", "Invalid control intent")

    @ExceptionHandler(DataAccessException::class, IllegalStateException::class)
    fun unavailable() =
        runControlProblem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "run-control-unavailable",
            "Control acknowledgement unavailable",
        )
}

/** No actor, holder, TTL or clock is caller-selectable. The optional fence is required for renew/release only. */
data class BrowserRunControlRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val action: RunControlAction,
    val control: RunControlFence? = null,
)

/** Historical non-secret coordinates only; must observe status again before claiming current control. */
data class BrowserRunControlReceipt(
    val commandId: UUID,
    val runId: UUID,
    val action: RunControlAction,
    val supervisionRevision: Long,
    val control: RunControlFence,
    val changedAt: Instant,
    val expiresAt: Instant,
    val recordedAt: Instant,
)
