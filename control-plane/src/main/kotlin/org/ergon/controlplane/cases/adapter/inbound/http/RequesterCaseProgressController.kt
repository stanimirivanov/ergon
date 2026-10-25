package org.ergon.controlplane.cases.adapter.inbound.http

import org.ergon.controlplane.cases.application.RequesterCaseNotFoundException
import org.ergon.controlplane.cases.application.RequesterCaseProgress
import org.ergon.controlplane.cases.application.RequesterCaseProgressService
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/** Requester-only transport boundary; identity is never taken from path or body actor fields. */
@RestController
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["audience"], havingValue = "requester")
class RequesterCaseProgressController(
    private val actors: AuthenticatedHumanActorResolver,
    private val progress: RequesterCaseProgressService,
) {
    /** Returns public progress or the same 404 for every non-visible case; tenant registration still requires 403. */
    @GetMapping("/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/progress")
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): RequesterCaseProgress =
        progress.progress(
            tenantId = tenantId,
            actorId =
                actors
                    .resolve(tenantId, principal)
                    .actor.id.value,
            caseId = caseId,
        )

    @ExceptionHandler(RequesterCaseNotFoundException::class)
    fun notFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Requester case not found").apply {
            type = URI.create("urn:ergon:problem:requester-case-not-found")
            title = "Requester case not found"
        }
}
