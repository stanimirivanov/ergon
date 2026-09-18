package org.ergon.controlplane.followup.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Authenticated internal boundary for retrieving durable resolver follow-up work. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/human-follow-ups")
@SecurityRequirement(name = "bearerAuth")
class HumanFollowUpWorkItemController(
    private val actors: AuthenticatedHumanActorResolver,
    private val service: HumanFollowUpWorkItemQueryService,
) {
    /** Lists the oldest open work visible under the caller's current resolver authority. */
    @GetMapping
    fun listOpen(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        afterOpenedAt: Instant?,
        @RequestParam(required = false) afterWorkItemId: UUID?,
        authentication: JwtAuthenticationToken,
    ): HumanFollowUpWorkItemPageResponse {
        val actor = actors.resolve(tenantId, authentication)
        val page = service.listOpen(tenantId, actor.actor.id.value, limit, afterOpenedAt, afterWorkItemId)
        return HumanFollowUpWorkItemPageResponse(
            page.items.map(StoredHumanFollowUpWorkItem::toResponse),
            page.nextCursor?.let { HumanFollowUpWorkItemCursorResponse(it.openedAt, it.workItemId.value) },
        )
    }

    /** Returns one work item only while the authenticated actor has current resolver authority. */
    @GetMapping("/{workItemId}")
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        authentication: JwtAuthenticationToken,
    ): HumanFollowUpWorkItemResponse {
        val actor = actors.resolve(tenantId, authentication)
        return service.get(tenantId, workItemId, actor.actor.id.value).toResponse()
    }
}

/** Bounded oldest-first inbox page. */
data class HumanFollowUpWorkItemPageResponse(
    val items: List<HumanFollowUpWorkItemResponse>,
    val nextCursor: HumanFollowUpWorkItemCursorResponse?,
)

/** Exact keyset values to supply when requesting the next inbox page. */
data class HumanFollowUpWorkItemCursorResponse(
    val afterOpenedAt: Instant,
    val afterWorkItemId: UUID,
)

/** Immutable source and current lifecycle facts for one human follow-up item. */
data class HumanFollowUpWorkItemResponse(
    val workItemId: UUID,
    val caseId: UUID,
    val runId: UUID,
    val escalationEventId: UUID,
    val reason: String,
    val status: String,
    val openedAt: Instant,
    val recordedAt: Instant,
)

private fun StoredHumanFollowUpWorkItem.toResponse() =
    HumanFollowUpWorkItemResponse(
        item.id.value,
        item.caseId.value,
        item.runId.value,
        item.escalationEventId.value,
        item.reason.name,
        item.status.name,
        item.openedAt,
        recordedAt,
    )
