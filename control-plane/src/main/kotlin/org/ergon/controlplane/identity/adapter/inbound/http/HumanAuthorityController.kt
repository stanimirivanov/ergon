package org.ergon.controlplane.identity.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.ergon.controlplane.identity.application.ApprovalAuthorityEvidenceView
import org.ergon.controlplane.identity.application.AttestApprovalAuthorityCommand
import org.ergon.controlplane.identity.application.HumanAuthorityService
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.identity.domain.HumanActor
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
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

/** Internal HTTP adapter for human identity bindings and approval-authority attestations. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/human-actors")
class HumanAuthorityController(
    private val service: HumanAuthorityService,
) {
    @PostMapping
    fun registerActor(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: RegisterHumanActorRequest,
    ): ResponseEntity<HumanActorResponse> {
        val stored = service.registerActor(tenantId, request.identityProvider, request.subject)
        return ResponseEntity
            .created(actorLocation(tenantId, stored.actor.id.value))
            .body(stored.toResponse())
    }

    @GetMapping("/{actorId}")
    fun getActor(
        @PathVariable tenantId: UUID,
        @PathVariable actorId: UUID,
    ): HumanActorResponse = service.getActor(tenantId, actorId).toResponse()

    @PostMapping("/{actorId}/approval-authority-evidence")
    fun attest(
        @PathVariable tenantId: UUID,
        @PathVariable actorId: UUID,
        @Valid @RequestBody request: AttestApprovalAuthorityRequest,
    ): ResponseEntity<ApprovalAuthorityEvidenceResponse> {
        val stored =
            service.attest(
                AttestApprovalAuthorityCommand(
                    tenantId = tenantId,
                    actorId = actorId,
                    authority = request.authority.toAuthority(),
                    caseId = request.caseId,
                    sourceProvider = request.sourceProvider,
                    sourceReference = request.sourceReference,
                    expiresAt = request.expiresAt,
                ),
            )
        return ResponseEntity
            .created(evidenceLocation(tenantId, stored.stored.evidence.id.value))
            .body(stored.toResponse())
    }

    @GetMapping("/approval-authority-evidence/{evidenceId}")
    fun getEvidence(
        @PathVariable tenantId: UUID,
        @PathVariable evidenceId: UUID,
    ): ApprovalAuthorityEvidenceResponse = service.getEvidence(tenantId, evidenceId).toResponse()
}

/** Request that binds an opaque provider subject to a new tenant-scoped human actor. */
data class RegisterHumanActorRequest(
    @field:Pattern(regexp = HumanActor.IDENTITY_PROVIDER_PATTERN)
    @field:Size(max = HumanActor.MAX_IDENTITY_PROVIDER_LENGTH)
    val identityProvider: String,
    @field:NotBlank @field:Size(max = HumanActor.MAX_SUBJECT_LENGTH)
    val subject: String,
)

/** Request containing externally sourced, bounded evidence of human approval authority. */
data class AttestApprovalAuthorityRequest(
    @field:Pattern(regexp = "REQUESTER|RESOLVER")
    val authority: String,
    val caseId: UUID?,
    @field:Pattern(regexp = ApprovalAuthorityEvidenceSource.PROVIDER_PATTERN)
    @field:Size(max = ApprovalAuthorityEvidenceSource.MAX_PROVIDER_LENGTH)
    val sourceProvider: String,
    @field:NotBlank @field:Size(max = ApprovalAuthorityEvidenceSource.MAX_REFERENCE_LENGTH)
    val sourceReference: String,
    val expiresAt: Instant,
)

/** Stable HTTP representation of an immutable human actor identity binding. */
data class HumanActorResponse(
    val actorId: UUID,
    val identityProvider: String,
    val subject: String,
    val registeredAt: Instant,
    val recordedAt: Instant,
)

/** Stable HTTP representation of one immutable authority attestation and current status. */
data class ApprovalAuthorityEvidenceResponse(
    val evidenceId: UUID,
    val actorId: UUID,
    val authority: String,
    val caseId: UUID?,
    val sourceProvider: String,
    val sourceReference: String,
    val attestedAt: Instant,
    val expiresAt: Instant,
    val recordedAt: Instant,
    val status: String,
)

private fun StoredHumanActor.toResponse() =
    HumanActorResponse(
        actorId = actor.id.value,
        identityProvider = actor.identityProvider,
        subject = actor.subject,
        registeredAt = registeredAt,
        recordedAt = recordedAt,
    )

private fun ApprovalAuthorityEvidenceView.toResponse(): ApprovalAuthorityEvidenceResponse {
    val evidence = stored.evidence
    return ApprovalAuthorityEvidenceResponse(
        evidenceId = evidence.id.value,
        actorId = evidence.actorId.value,
        authority = evidence.authority.name,
        caseId = evidence.caseId?.value,
        sourceProvider = evidence.source.provider,
        sourceReference = evidence.source.reference,
        attestedAt = evidence.attestedAt,
        expiresAt = evidence.expiresAt,
        recordedAt = stored.recordedAt,
        status = status.name,
    )
}

private fun String.toAuthority(): ApprovalAuthority =
    try {
        ApprovalAuthority.valueOf(this)
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("authority must be REQUESTER or RESOLVER", exception)
    }

private fun actorLocation(
    tenantId: UUID,
    actorId: UUID,
) = URI.create("/internal/v1/tenants/$tenantId/human-actors/$actorId")

private fun evidenceLocation(
    tenantId: UUID,
    evidenceId: UUID,
) = URI.create("/internal/v1/tenants/$tenantId/human-actors/approval-authority-evidence/$evidenceId")
