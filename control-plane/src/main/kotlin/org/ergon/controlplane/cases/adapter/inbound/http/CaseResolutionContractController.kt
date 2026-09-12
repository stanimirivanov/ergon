package org.ergon.controlplane.cases.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.PinResolutionContractCommand
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Internal adapter for fixing the immutable resolution instructions used by a case. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-contract")
class CaseResolutionContractController(
    private val commandService: CaseCommandService,
) {
    @PostMapping
    fun pin(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody request: PinResolutionContractRequest,
    ): ResponseEntity<CaseWriteResponse> {
        val result =
            commandService.pinResolutionContract(
                PinResolutionContractCommand(
                    tenantId = tenantId,
                    caseId = caseId,
                    expectedVersion = parseVersion(ifMatch),
                    contractKey = request.key,
                    contractRevision = request.revision,
                ),
            )
        return ResponseEntity.ok().eTag(result.etag()).body(result.toResponse())
    }
}

/** Identifies one exact published contract revision; no alias can be pinned. */
data class PinResolutionContractRequest(
    @field:Pattern(regexp = "[a-z0-9][a-z0-9-]{0,99}") val key: String,
    @field:Min(1) val revision: Int,
)
