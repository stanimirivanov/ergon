package org.ergon.controlplane.followup.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRecording
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpInboxQuery
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpWork
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Authenticated internal boundary for discovering and claiming durable resolver work. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/human-follow-ups")
@SecurityRequirement(name = "bearerAuth")
class HumanFollowUpWorkItemController(
    private val actors: AuthenticatedHumanActorResolver,
    private val service: HumanFollowUpWorkItemQueryService,
    private val claims: HumanFollowUpClaimService,
) {
    /** Lists the oldest unclaimed work, optionally from one queue, under current resolver authority. */
    @GetMapping
    fun listOpen(
        @PathVariable tenantId: UUID,
        @ModelAttribute request: HumanFollowUpInboxRequest,
        authentication: JwtAuthenticationToken,
    ): HumanFollowUpWorkItemPageResponse {
        val actor = actors.resolve(tenantId, authentication)
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
        return HumanFollowUpWorkItemPageResponse(
            page.items.map(StoredHumanFollowUpWorkItem::toResponse),
            page.nextCursor?.let { HumanFollowUpWorkItemCursorResponse(it.openedAt, it.workItemId.value) },
        )
    }

    /** Lists active work owned by the current resolver in oldest-claimed-first order. */
    @GetMapping("/owned")
    fun listOwned(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        afterClaimedAt: Instant?,
        @RequestParam(required = false) afterClaimId: UUID?,
        authentication: JwtAuthenticationToken,
    ): ResolverOwnedHumanFollowUpPageResponse {
        val actor = actors.resolve(tenantId, authentication)
        val page = claims.listOwned(tenantId, actor.actor.id.value, limit, afterClaimedAt, afterClaimId)
        return ResolverOwnedHumanFollowUpPageResponse(
            page.items.map(ResolverOwnedHumanFollowUpWork::toResponse),
            page.nextCursor?.let { ResolverOwnedHumanFollowUpCursorResponse(it.claimedAt, it.claimId.value) },
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

    /** Claims open work for the current resolver or replays their existing claim. */
    @PostMapping("/{workItemId}/claims")
    fun claim(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        authentication: JwtAuthenticationToken,
    ): ResponseEntity<HumanFollowUpClaimResponse> {
        val actor = actors.resolve(tenantId, authentication)
        val recording = claims.claim(tenantId, workItemId, actor.actor.id.value)
        val location = claimLocation(tenantId, workItemId, recording.storedClaim.claim.id.value)
        return if (recording.created) {
            ResponseEntity.created(location).body(recording.toResponse())
        } else {
            ResponseEntity.ok().location(location).body(recording.toResponse())
        }
    }

    /** Returns one claim only while the caller has current resolver authority. */
    @GetMapping("/{workItemId}/claims/{claimId}")
    fun getClaim(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @PathVariable claimId: UUID,
        authentication: JwtAuthenticationToken,
    ): HumanFollowUpClaimResponse {
        val actor = actors.resolve(tenantId, authentication)
        return claims.get(tenantId, workItemId, claimId, actor.actor.id.value).toResponse()
    }
}

/**
 * Optional shared-inbox filter and keyset page supplied as query parameters.
 *
 * Mutable properties are confined to this HTTP binding type because Spring
 * MVC populates model attributes through setters. The controller immediately
 * copies them into an immutable application query.
 */
class HumanFollowUpInboxRequest {
    var queueKey: String? = null
    var limit: Int = DEFAULT_LIMIT

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    var afterOpenedAt: Instant? = null
    var afterWorkItemId: UUID? = null

    private companion object {
        const val DEFAULT_LIMIT = 50
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

/** Active work owned by the authenticated resolver and its immutable ownership record. */
data class ResolverOwnedHumanFollowUpResponse(
    val workItem: HumanFollowUpWorkItemResponse,
    val claim: HumanFollowUpClaimResponse,
)

/** Bounded oldest-claimed-first page of the authenticated resolver's active work. */
data class ResolverOwnedHumanFollowUpPageResponse(
    val items: List<ResolverOwnedHumanFollowUpResponse>,
    val nextCursor: ResolverOwnedHumanFollowUpCursorResponse?,
)

/** Exact claim position to supply when requesting the next owned-work page. */
data class ResolverOwnedHumanFollowUpCursorResponse(
    val afterClaimedAt: Instant,
    val afterClaimId: UUID,
)

/** Immutable source and current lifecycle facts for one human follow-up item. */
data class HumanFollowUpWorkItemResponse(
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

/** Immutable ownership and authority attribution for one resolver claim. */
data class HumanFollowUpClaimResponse(
    val claimId: UUID,
    val workItemId: UUID,
    val resolverActorId: UUID,
    val authorityEvidenceId: UUID,
    val claimedAt: Instant,
    val recordedAt: Instant,
)

private fun StoredHumanFollowUpWorkItem.toResponse() =
    HumanFollowUpWorkItemResponse(
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

private fun ResolverOwnedHumanFollowUpWork.toResponse() =
    ResolverOwnedHumanFollowUpResponse(
        workItem.toResponse(),
        claim.toResponse(),
    )

private fun HumanFollowUpClaimRecording.toResponse() = storedClaim.toResponse()

private fun StoredHumanFollowUpClaim.toResponse() =
    HumanFollowUpClaimResponse(
        claim.id.value,
        claim.workItemId.value,
        claim.resolverActorId.value,
        claim.authorityEvidenceId.value,
        claim.claimedAt,
        recordedAt,
    )

private fun claimLocation(
    tenantId: UUID,
    workItemId: UUID,
    claimId: UUID,
) = URI.create("/internal/v1/tenants/$tenantId/human-follow-ups/$workItemId/claims/$claimId")
