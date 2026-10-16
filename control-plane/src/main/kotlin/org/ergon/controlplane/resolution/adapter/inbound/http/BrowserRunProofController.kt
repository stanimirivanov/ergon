package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.FinalizeRunProofCommand
import org.ergon.controlplane.resolution.application.RunProofFinalizationConflictException
import org.ergon.controlplane.resolution.application.RunProofFinalizationDisabledException
import org.ergon.controlplane.resolution.application.RunProofFinalizationService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Session and CSRF protected acceptance of reviewed proof; no connector or forced closure boundary. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/proof-finalizations")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunProofController(
    private val actors: AuthenticatedHumanActorResolver,
    private val finalizations: RunProofFinalizationService,
) {
    /** Returns identical persisted acknowledgement on exact originating-actor replay. */
    @PostMapping
    fun finalizeProof(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunProofRequest,
    ): ResponseEntity<BrowserRunProofReceipt> {
        val actor = actors.resolve(tenantId, principal)
        val result =
            finalizations.finalizeProof(
                FinalizeRunProofCommand(
                    tenantId = tenantId,
                    runId = runId,
                    actorId = actor.actor.id.value,
                    commandId = body.commandId,
                    expectedSupervisionRevision = RunSupervisionRevision(body.expectedSupervisionRevision),
                    expectedRunVersion = body.expectedRunVersion,
                    expectedCaseVersion = body.expectedCaseVersion,
                    factId = body.factId,
                    observationId = body.observationId,
                ),
            )
        val stored = result.stored
        val command = stored.command
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(
                BrowserRunProofReceipt(
                    commandId = command.commandId,
                    runId = runId,
                    supervisionRevision = command.expectedSupervisionRevision.value,
                    expectedRunVersion = command.expectedRunVersion,
                    assessedCaseVersion = command.expectedCaseVersion,
                    factId = command.factId,
                    observationId = command.observationId,
                    runSequence = 2,
                    caseClosureStreamVersion = command.expectedCaseVersion + 1,
                    acceptedAt = stored.acceptedAt,
                    recordedAt = stored.recordedAt,
                ),
            )
    }

    @ExceptionHandler(RunProofFinalizationConflictException::class)
    fun conflict(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "run-proof-finalization-conflict", "Reviewed proof is stale or conflicting")

    @ExceptionHandler(RunProofFinalizationDisabledException::class)
    fun disabled(): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "run-proof-finalization-disabled",
            "Browser proof finalization is disabled",
        )

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "invalid-run-proof-finalization", "Invalid proof finalization intent")

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                this.type = URI.create("urn:ergon:problem:$type")
                title = detail
            },
        )
}

/** Exact reviewed coordinates only; identity, authority, condition and acceptance time are server-owned. */
data class BrowserRunProofRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val expectedRunVersion: Long,
    val expectedCaseVersion: Long,
    val factId: UUID,
    val observationId: UUID,
)

/** Historical acceptance and closure acknowledgement, not current access or a fresh external-system check. */
data class BrowserRunProofReceipt(
    val commandId: UUID,
    val runId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val assessedCaseVersion: Long,
    val factId: UUID,
    val observationId: UUID,
    val runSequence: Long,
    val caseClosureStreamVersion: Long,
    val acceptedAt: Instant,
    val recordedAt: Instant,
)
