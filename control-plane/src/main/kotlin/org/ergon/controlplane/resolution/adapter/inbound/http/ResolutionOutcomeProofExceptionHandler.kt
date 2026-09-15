package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofChangedException
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofPendingException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunNotVerifyingException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps outcome-proof assessment failures to stable RFC 9457 documents. */
@RestControllerAdvice(
    assignableTypes = [
        ResolutionOutcomeProofController::class,
        ResolutionOutcomeProofAcceptanceController::class,
    ],
)
class ResolutionOutcomeProofExceptionHandler {
    @ExceptionHandler(ResolutionRunNotFoundException::class)
    fun runNotFound(exception: ResolutionRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(ResolutionRunNotVerifyingException::class)
    fun runNotVerifying(exception: ResolutionRunNotVerifyingException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-run-not-verifying",
            "Resolution run not verifying",
            exception,
        ).apply { setProperty("state", exception.state) }

    @ExceptionHandler(ResolutionOutcomeProofPendingException::class)
    fun proofPending(exception: ResolutionOutcomeProofPendingException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-outcome-proof-pending",
            "Resolution outcome proof pending",
            exception,
        ).apply {
            setProperty("reason", exception.reason.name)
            setProperty("caseStreamVersion", exception.caseStreamVersion)
        }

    @ExceptionHandler(ResolutionOutcomeProofChangedException::class)
    fun proofChanged(exception: ResolutionOutcomeProofChangedException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-outcome-proof-changed",
            "Resolution outcome proof changed",
            exception,
        ).apply {
            setProperty("assessedVersion", exception.assessedVersion)
            setProperty("actualVersion", exception.actualVersion)
        }

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
