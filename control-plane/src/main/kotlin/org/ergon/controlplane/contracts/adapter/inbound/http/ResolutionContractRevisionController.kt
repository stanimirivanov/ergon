package org.ergon.controlplane.contracts.adapter.inbound.http

import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionService
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.identity.domain.TenantId
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Internal HTTP adapter for publishing and reading tenant contract revisions. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-contracts")
class ResolutionContractRevisionController(
    private val revisionService: ResolutionContractRevisionService,
) {
    @PostMapping(
        consumes = ["application/yaml", "text/yaml"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun publish(
        @PathVariable tenantId: UUID,
        @RequestBody document: String,
    ): ResponseEntity<StoredResolutionContractRevisionResponse> {
        val stored = revisionService.publish(TenantId(tenantId), document)
        val contract = stored.contract
        val location =
            URI.create(
                "/internal/v1/tenants/$tenantId/resolution-contracts/" +
                    "${contract.key.value}/revisions/${contract.revision.value}",
            )
        return ResponseEntity.created(location).body(stored.toResponse())
    }

    @GetMapping(
        "/{key}/revisions/{revision}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable key: String,
        @PathVariable revision: Int,
    ): StoredResolutionContractRevisionResponse =
        revisionService
            .get(
                TenantId(tenantId),
                keyOf(key),
                revisionOf(revision),
            ).toResponse()
}

/** Durable contract response with normalized meaning and database recording time. */
data class StoredResolutionContractRevisionResponse(
    val contract: ValidatedResolutionContractResponse,
    val recordedAt: Instant,
)

/** Signals a path identity that cannot name a valid contract revision. */
class InvalidContractRevisionIdentityException(
    message: String,
    cause: IllegalArgumentException,
) : RuntimeException(message, cause)

private fun StoredResolutionContractRevision.toResponse() =
    StoredResolutionContractRevisionResponse(
        contract = contract.toResponse(),
        recordedAt = recordedAt,
    )

private fun keyOf(value: String): ResolutionContractKey = domainIdentity { ResolutionContractKey.of(value) }

private fun revisionOf(value: Int): ResolutionContractRevision = domainIdentity { ResolutionContractRevision.of(value) }

private fun <T> domainIdentity(block: () -> T): T =
    try {
        block()
    } catch (exception: IllegalArgumentException) {
        throw InvalidContractRevisionIdentityException(
            exception.message ?: "invalid contract revision identity",
            exception,
        )
    }
