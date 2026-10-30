package org.ergon.controlplane.evaluation.adapter.inbound.browser

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.contracts.adapter.inbound.document.PromotionDocumentCodec
import org.ergon.controlplane.evaluation.application.SimulationLabPromotions
import org.ergon.controlplane.evaluation.application.SimulationLabReports
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Session actor, tenant registration and current authority are mandatory; the BFF filter enforces CSRF. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenant}/simulation-lab")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class SimulationLabController(
    private val actors: AuthenticatedHumanActorResolver,
    private val reports: SimulationLabReports,
    private val promotions: SimulationLabPromotions,
    private val documents: SimulationBrowserDocuments,
    private val commands: PromotionDocumentCodec,
) {
    @GetMapping("/reports")
    fun list(
        @PathVariable tenant: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) after: UUID?,
    ): ResponseEntity<*> = ok(reports.list(tenant, actor(tenant, principal), limit, after))

    @GetMapping("/reports/{id}")
    fun detail(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<*> = ok(reports.get(tenant, actor(tenant, principal), id))

    @PostMapping("/contracts/{key}/promotion-review")
    fun review(
        @PathVariable tenant: UUID,
        @PathVariable key: String,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = actor(tenant, principal)
        val input = documents.review(request)
        return ok(promotions.review(tenant, actor, key, input.reports, input.rationale))
    }

    @PostMapping("/promotion-commands/{id}")
    fun confirm(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = actor(tenant, principal)
        val result = promotions.confirm(tenant, actor, id, commands.promotion(documents.bytes(request)))
        return ResponseEntity
            .status(
                if (result.created) HttpStatus.CREATED else HttpStatus.OK,
            ).cacheControl(CacheControl.noStore())
            .body(result)
    }

    private fun actor(
        tenant: UUID,
        principal: OidcUser,
    ): UUID =
        actors
            .resolve(tenant, principal)
            .actor.id.value

    private fun ok(value: Any): ResponseEntity<*> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
