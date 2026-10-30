package org.ergon.controlplane.evaluation.adapter.inbound.browser

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evaluation.application.SimulationAuthorityKind
import org.ergon.controlplane.evaluation.application.SimulationLabAuthority
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Trusted issuer boundary only; no browser route grants or revokes authority. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenant}/simulation-lab-authorities")
class SimulationLabAuthorityController(
    private val authority: SimulationLabAuthority,
    private val documents: SimulationBrowserDocuments,
) {
    @PostMapping("/viewers/{id}")
    fun attest(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val result = authority.attest(tenant, id, documents.viewer(request))
        return ResponseEntity
            .status(
                if (result.created) HttpStatus.CREATED else HttpStatus.OK,
            ).cacheControl(CacheControl.noStore())
            .body(result.authority)
    }

    @PostMapping("/{kind}/{id}/revocation")
    fun revoke(
        @PathVariable tenant: UUID,
        @PathVariable kind: SimulationAuthorityKind,
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val result = authority.revoke(tenant, kind, id, documents.revocation(request))
        return ResponseEntity
            .status(
                if (result.created) HttpStatus.CREATED else HttpStatus.OK,
            ).cacheControl(CacheControl.noStore())
            .body(result)
    }
}
