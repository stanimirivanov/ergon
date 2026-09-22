package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpInboxQuery
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Browser-session boundary for the resolver's shared human follow-up inbox. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups")
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "true",
)
class BrowserHumanFollowUpInboxController(
    private val actors: AuthenticatedHumanActorResolver,
    private val service: HumanFollowUpWorkItemQueryService,
) {
    /**
     * Lists the oldest unclaimed work visible under the authenticated actor's current resolver authority.
     *
     * The verified OIDC subject is resolved server-side and is never accepted from query input or returned
     * in the response. A caller without current resolver authority receives an empty page so this boundary
     * cannot disclose whether follow-up work exists in the tenant.
     */
    @GetMapping
    fun listOpen(
        @PathVariable tenantId: UUID,
        @ModelAttribute request: BrowserHumanFollowUpInboxRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): BrowserHumanFollowUpPageResponse {
        val actor = actors.resolve(tenantId, principal)
        val page =
            service.listOpen(
                HumanFollowUpInboxQuery(
                    tenantId,
                    actor.actor.id.value,
                    request.queueKey,
                    request.limit,
                    request.afterOpenedAt,
                    request.afterWorkItemId,
                ),
            )
        return BrowserHumanFollowUpPageResponse(
            page.items.map(StoredHumanFollowUpWorkItem::toBrowserResponse),
            page.nextCursor?.let { BrowserHumanFollowUpCursorResponse(it.openedAt, it.workItemId.value) },
        )
    }
}

/**
 * Optional queue filter and exact keyset position supplied by the browser.
 *
 * Mutable properties are confined to this Spring MVC binding type and copied immediately into an
 * immutable application query. Cursor components must be supplied together; `limit` must be in `1..100`.
 */
class BrowserHumanFollowUpInboxRequest {
    var queueKey: String? = null
    var limit: Int = DEFAULT_LIMIT

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    var afterOpenedAt: Instant? = null
    var afterWorkItemId: UUID? = null

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/** Bounded oldest-first browser inbox page without identity-provider data. */
data class BrowserHumanFollowUpPageResponse(
    val items: List<BrowserHumanFollowUpWorkItemResponse>,
    val nextCursor: BrowserHumanFollowUpCursorResponse?,
)

/** Exact keyset values the browser must retain together when requesting the next page. */
data class BrowserHumanFollowUpCursorResponse(
    val afterOpenedAt: Instant,
    val afterWorkItemId: UUID,
)

/** Immutable source, routing, and lifecycle facts needed to render one shared-inbox row. */
data class BrowserHumanFollowUpWorkItemResponse(
    val workItemId: UUID,
    val caseId: UUID,
    val runId: UUID,
    val escalationEventId: UUID,
    val reason: String,
    val queueKey: String,
    val status: String,
    val openedAt: Instant,
    val recordedAt: Instant,
)

private fun StoredHumanFollowUpWorkItem.toBrowserResponse() =
    BrowserHumanFollowUpWorkItemResponse(
        item.id.value,
        item.caseId.value,
        item.runId.value,
        item.escalationEventId.value,
        item.reason.name,
        item.queueKey.value,
        item.status.name,
        item.openedAt,
        recordedAt,
    )
