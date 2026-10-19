package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.RunControlConflictException
import org.ergon.controlplane.resolution.application.RunControlDisabledException
import org.ergon.controlplane.resolution.application.RunControlRequiredException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Shared safe failure vocabulary; each command keeps its existing business-specific failure mappings. */
@RestControllerAdvice(
    assignableTypes = [
        BrowserRunControlController::class, BrowserRunSupervisorHandoverController::class,
        BrowserRunApprovalController::class, BrowserRunProofController::class, BrowserRunExecutionController::class,
        BrowserRunRetryController::class,
        BrowserRunEscalationController::class,
        BrowserRunCancellationController::class,
    ],
)
@Order(Ordered.HIGHEST_PRECEDENCE)
class BrowserRunControlExceptionHandler {
    @ExceptionHandler(RunControlRequiredException::class)
    fun required() = runControlProblem(HttpStatus.CONFLICT, "run-control-required", "Browser control is required")

    @ExceptionHandler(RunControlConflictException::class)
    fun conflict() =
        runControlProblem(
            HttpStatus.CONFLICT,
            "run-control-conflict",
            "Browser control intent is stale or unavailable",
        )

    @ExceptionHandler(RunControlDisabledException::class)
    fun disabled() =
        runControlProblem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "run-control-disabled",
            "Browser control is disabled",
        )
}

internal fun runControlProblem(
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
