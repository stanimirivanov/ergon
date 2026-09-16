package org.ergon.controlplane.followup.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
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
