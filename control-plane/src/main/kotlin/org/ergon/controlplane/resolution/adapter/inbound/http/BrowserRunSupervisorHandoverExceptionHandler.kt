package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverConflictException
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverDisabledException
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverUnavailableException
import org.ergon.controlplane.resolution.application.RunSupervisorNotEligibleException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

/** Stable, no-store failure contracts without raw exceptions, incumbent identities, or SQL diagnostics. */
@RestControllerAdvice(
    assignableTypes = [
        BrowserRunSupervisorHandoverController::class,
        BrowserRunApprovalController::class,
        BrowserRunProofController::class,
        BrowserRunExecutionController::class,
        BrowserRunRetryController::class,
        BrowserRunEscalationController::class,
    ],
)
class BrowserRunSupervisorHandoverExceptionHandler {
    @ExceptionHandler(AssignedRunNotFoundException::class)
    fun missing(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "assigned-resolution-run-not-found", "Assigned run unavailable")

    @ExceptionHandler(RunSupervisorHandoverConflictException::class)
    fun conflict(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "run-supervisor-handover-conflict", "Handover intent is stale or conflicting")

    @ExceptionHandler(RunSupervisorHandoverDisabledException::class)
    fun disabled(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "run-supervisor-handover-disabled", "Supervisor handover is disabled")

    @ExceptionHandler(RunSupervisorHandoverUnavailableException::class)
    fun unavailable(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "run-supervisor-handover-unavailable", "Supervisor handover is unavailable")

    @ExceptionHandler(RunSupervisorNotEligibleException::class)
    fun recipient(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "run-supervisor-not-eligible", "Select another eligible tenant resolver")

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "invalid-run-supervisor-handover", "Invalid supervisor handover request")

    @ExceptionHandler(AuthenticatedHumanActorNotRegisteredException::class)
    fun unregistered(): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.FORBIDDEN,
            "human-actor-not-registered",
            "Actor not registered",
        )

    @ExceptionHandler(UntrustedHumanIdentityIssuerException::class)
    fun issuer(): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.FORBIDDEN,
            "untrusted-human-identity-issuer",
            "Identity rejected",
        )

    @ExceptionHandler(InvalidAuthenticatedHumanIdentityException::class)
    fun identity(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.FORBIDDEN, "invalid-authenticated-human-identity", "Identity rejected")

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
