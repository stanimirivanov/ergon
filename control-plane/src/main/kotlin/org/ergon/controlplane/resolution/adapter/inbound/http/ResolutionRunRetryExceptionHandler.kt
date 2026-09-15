package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunNotReadyException
import org.ergon.controlplane.resolution.application.ResolutionRunPolicyDeniedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryLimitReachedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryPlanChangedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryStateException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps explicit retry failures to stable RFC 9457 problem documents. */
@RestControllerAdvice(assignableTypes = [ResolutionRunRetryController::class])
class ResolutionRunRetryExceptionHandler {
    @ExceptionHandler(ResolutionRunNotFoundException::class)
    fun runNotFound(exception: ResolutionRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(ResolutionRunRetryStateException::class)
    fun retryState(exception: ResolutionRunRetryStateException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-run-not-retryable",
            "Resolution run is not retryable",
            exception,
        ).apply { setProperty("state", exception.state) }

    @ExceptionHandler(ResolutionRunRetryPlanChangedException::class)
    fun retryPlanChanged(exception: ResolutionRunRetryPlanChangedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "resolution-run-retry-plan-changed", "Retry operation changed", exception)

    @ExceptionHandler(ResolutionRunRetryLimitReachedException::class)
    fun retryLimitReached(exception: ResolutionRunRetryLimitReachedException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-run-retry-attempt-limit-reached",
            "Retry attempt limit reached",
            exception,
        ).apply {
            setProperty("policyRevision", exception.policyRevision.value)
            setProperty("sourceAttemptNumber", exception.sourceAttemptNumber)
            setProperty("maximumAttempts", exception.maximumAttempts)
            setProperty("reason", exception.reason.name)
        }

    @ExceptionHandler(ResolutionRunNotReadyException::class)
    fun runNotReady(exception: ResolutionRunNotReadyException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "resolution-run-not-ready", "Resolution run not ready", exception).apply {
            setProperty("readiness", exception.readiness)
        }

    @ExceptionHandler(ResolutionRunPolicyDeniedException::class)
    fun policyDenied(exception: ResolutionRunPolicyDeniedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "resolution-run-policy-denied", "Retry denied by policy", exception).apply {
            setProperty("reason", exception.reason)
        }

    @ExceptionHandler(ConcurrentCaseModificationException::class)
    fun staleVersion(exception: ConcurrentCaseModificationException): ProblemDetail =
        problem(HttpStatus.PRECONDITION_FAILED, "stale-case-version", "Stale case version", exception).apply {
            setProperty("expectedVersion", exception.expectedVersion)
            setProperty("actualVersion", exception.actualVersion)
        }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingPrecondition(exception: MissingRequestHeaderException): ProblemDetail =
        problem(HttpStatus.PRECONDITION_REQUIRED, "missing-precondition", "Missing request precondition", exception)

    @ExceptionHandler(InvalidRunVersionPreconditionException::class, IllegalArgumentException::class)
    fun invalidCommand(exception: RuntimeException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid-resolution-run-retry", "Invalid resolution run retry", exception)

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        exception: Exception,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
            this.title = title
        }
}
