package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanNotificationAudience
import org.ergon.controlplane.followup.application.HumanNotificationKind
import org.ergon.controlplane.followup.application.HumanNotificationService
import org.ergon.controlplane.followup.application.HumanNotificationTargetKind
import org.ergon.controlplane.followup.application.HumanNotificationsForbiddenException
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.adapter.inbound.security.BrowserSessionAudience
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Fixed-client session identity and deployment audience, never caller-selected recipient or target authority. */
@RestController
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserHumanNotificationController(
    private val actors: AuthenticatedHumanActorResolver,
    private val audience: BrowserSessionAudience,
    private val notifications: HumanNotificationService,
) {
    /** Read-only bounded history; clients must reacquire target context through existing protected APIs. */
    @GetMapping("/bff/v1/tenants/{tenantId}/notifications")
    fun list(
        @PathVariable tenantId: UUID,
        authentication: Authentication,
    ): ResponseEntity<BrowserHumanNotifications> {
        val token = authentication as? OAuth2AuthenticationToken
        val principal = token?.principal as? OidcUser
        if (token?.authorizedClientRegistrationId != audience.registrationId || principal == null) {
            throw InvalidBrowserOidcIdentityException()
        }
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val scope =
            if (audience == BrowserSessionAudience.REQUESTER) {
                HumanNotificationAudience.REQUESTER
            } else {
                HumanNotificationAudience.RESOLVER
            }
        val page = notifications.list(tenantId, actor, scope)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            BrowserHumanNotifications(
                page.items.map {
                    BrowserHumanNotification(
                        it.kind,
                        it.sourceId,
                        BrowserNotificationTarget(it.kind.targetKind, it.targetId),
                        it.deliveredAt,
                    )
                },
                page.truncated,
            ),
        )
    }

    @ExceptionHandler(HumanNotificationsForbiddenException::class)
    fun forbidden(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Notification feed is unavailable").apply {
            type = URI.create("urn:ergon:problem:human-notifications-forbidden")
        }
}

data class BrowserHumanNotifications(
    val items: List<BrowserHumanNotification>,
    val truncated: Boolean,
)

/** Safe metadata only; use the full (kind, sourceId, target) tuple as identity, never sourceId alone. */
data class BrowserHumanNotification(
    val kind: HumanNotificationKind,
    val sourceId: UUID,
    val target: BrowserNotificationTarget,
    val deliveredAt: Instant,
)

data class BrowserNotificationTarget(
    val kind: HumanNotificationTargetKind,
    val id: UUID,
)
