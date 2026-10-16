package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.RunExecutionConflictException
import org.ergon.controlplane.resolution.application.RunExecutionDisabledException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

/** Unexpected persistence errors may follow an admitted effect: never leak causes or suggest a new authorization. */
@RestControllerAdvice(assignableTypes = [BrowserRunExecutionController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class BrowserRunExecutionExceptionHandler {
    @ExceptionHandler(RunExecutionConflictException::class)
    fun conflict() = problem(HttpStatus.CONFLICT, "run-execution-conflict", "Execution intent is stale or unavailable")

    @ExceptionHandler(RunExecutionDisabledException::class)
    fun disabled() = problem(HttpStatus.SERVICE_UNAVAILABLE, "run-execution-disabled", "Browser execution is disabled")

    @ExceptionHandler(DataAccessException::class, IllegalStateException::class)
    fun unavailable() =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "run-execution-unavailable",
            "Execution acknowledgement is unavailable",
        )

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid() = problem(HttpStatus.BAD_REQUEST, "invalid-run-execution", "Invalid execution intent")

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
