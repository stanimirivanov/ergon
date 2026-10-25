package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.cases.application.RequesterCaseNotFoundException
import org.ergon.controlplane.followup.application.RequesterCaseQuestionsPage
import org.ergon.controlplane.followup.application.RequesterCaseQuestionsService
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

/** Requester-only narrow history; current resolver ownership is deliberately irrelevant to this audience. */
@RestController
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["audience"], havingValue = "requester")
class RequesterCaseQuestionsController(
    private val actors: AuthenticatedHumanActorResolver,
    private val questions: RequesterCaseQuestionsService,
) {
    /** Reads as the verified tenant actor; no caller-supplied identity or request UUID grants visibility. */
    @GetMapping("/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/information-requests")
    fun list(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): RequesterCaseQuestionsPage =
        questions.list(
            tenantId,
            caseId,
            actors
                .resolve(tenantId, principal)
                .actor.id.value,
        )

    @ExceptionHandler(RequesterCaseNotFoundException::class)
    fun notFound(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Requester case not found").apply {
            type = URI.create("urn:ergon:problem:requester-case-not-found")
            title = "Requester case not found"
        }
}
