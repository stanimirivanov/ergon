package org.ergon.controlplane.identity.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Resolves the verified bearer identity to its actor in the requested tenant. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/human-actor")
@SecurityRequirement(name = "bearerAuth")
class CurrentHumanActorController(
    private val actors: AuthenticatedHumanActorResolver,
) {
    /** Returns the current actor without accepting caller-controlled identity attributes. */
    @GetMapping
    fun currentActor(
        @PathVariable tenantId: UUID,
        authentication: JwtAuthenticationToken,
    ): HumanActorResponse = actors.resolve(tenantId, authentication).toResponse()
}
