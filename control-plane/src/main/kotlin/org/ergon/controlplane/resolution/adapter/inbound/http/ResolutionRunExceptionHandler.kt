package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.cases.application.CaseNotFoundException
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.resolution.application.ResolutionRunAlreadyExistsException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunNotReadyException
import org.ergon.controlplane.resolution.application.ResolutionRunPolicyDeniedException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps run application failures to stable RFC 9457 problem documents. */
@RestControllerAdvice(assignableTypes = [ResolutionRunController::class])
class ResolutionRunExceptionHandler {
    @ExceptionHandler(CaseNotFoundException::class)
    fun caseNotFound(exception: CaseNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "case-not-found", "Case not found", exception)

    @ExceptionHandler(ResolutionRunNotFoundException::class)
    fun runNotFound(exception: ResolutionRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(ResolutionRunNotReadyException::class)
    fun runNotReady(exception: ResolutionRunNotReadyException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "resolution-run-not-ready", "Resolution run not ready", exception).apply {
            setProperty("readiness", exception.readiness)
        }

    @ExceptionHandler(ResolutionRunPolicyDeniedException::class)
    fun policyDenied(exception: ResolutionRunPolicyDeniedException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-run-policy-denied",
            "Resolution run denied by policy",
            exception,
        ).apply {
            setProperty("reason", exception.reason)
        }

    @ExceptionHandler(ResolutionRunAlreadyExistsException::class)
    fun alreadyExists(exception: ResolutionRunAlreadyExistsException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "resolution-run-already-exists", "Resolution run already exists", exception)

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
        problem(HttpStatus.BAD_REQUEST, "invalid-resolution-run-command", "Invalid resolution run command", exception)

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
