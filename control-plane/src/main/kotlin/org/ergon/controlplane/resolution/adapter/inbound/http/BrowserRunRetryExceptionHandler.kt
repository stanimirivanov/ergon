package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.resolution.application.CurrentResolutionRecoveryAuthorityNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunNotReadyException
import org.ergon.controlplane.resolution.application.ResolutionRunPolicyDeniedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryLimitReachedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryPlanChangedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryReviewChangedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryStateException
import org.ergon.controlplane.resolution.application.RunRetryConflictException
import org.ergon.controlplane.resolution.application.RunRetryDisabledException
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentConflictException
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

/** No raw planner/SQL failures, private authority identities or unsafe automatic-retry hints cross the BFF. */
@RestControllerAdvice(assignableTypes = [BrowserRunRetryController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class BrowserRunRetryExceptionHandler {
    @ExceptionHandler(
        RunRetryConflictException::class,
        ResolutionRunRetryPlanChangedException::class,
        ResolutionRunNotReadyException::class,
        ResolutionRunPolicyDeniedException::class,
        ConcurrentCaseModificationException::class,
        RunSupervisorAssignmentConflictException::class,
        ResolutionRunRetryLimitReachedException::class,
        ResolutionRunRetryStateException::class,
        ResolutionRunRetryReviewChangedException::class,
    )
    fun conflict() = problem(HttpStatus.CONFLICT, "run-retry-conflict", "Retry intent is stale or unavailable")

    @ExceptionHandler(RunRetryDisabledException::class)
    fun disabled() = problem(HttpStatus.SERVICE_UNAVAILABLE, "run-retry-disabled", "Browser run retry is disabled")

    @ExceptionHandler(CurrentResolutionRecoveryAuthorityNotFoundException::class)
    fun missing() = problem(HttpStatus.NOT_FOUND, "assigned-resolution-run-not-found", "Assigned run unavailable")

    @ExceptionHandler(DataAccessException::class, IllegalStateException::class)
    fun unavailable() =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "run-retry-unavailable",
            "Retry information is unavailable",
        )

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid() = problem(HttpStatus.BAD_REQUEST, "invalid-run-retry", "Invalid retry intent")

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
