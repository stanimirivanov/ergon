package org.ergon.controlplane.cases.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.application.CaseDocumentaryBundleService
import org.ergon.controlplane.cases.application.PinCaseDocumentaryBundleCommand
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Internal attachment under the existing case command boundary; no browser authoring or authority change. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/cases/{caseId}/documentary-bundles")
class CaseDocumentaryBundleController(
    private val service: CaseDocumentaryBundleService,
) {
    @PostMapping
    fun pin(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody request: PinCaseDocumentaryBundleRequest,
    ): ResponseEntity<CaseWriteResponse> {
        val result =
            service.pin(
                PinCaseDocumentaryBundleCommand(
                    tenantId,
                    caseId,
                    parseVersion(ifMatch),
                    DocumentaryBundleRequirement(UUID.fromString(request.bundleId), request.sha256),
                ),
            )
        return ResponseEntity.ok().eTag(result.etag()).body(result.toResponse())
    }
}

data class PinCaseDocumentaryBundleRequest(
    @field:Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") val bundleId: String,
    @field:Pattern(regexp = "[a-f0-9]{64}") val sha256: String,
)
