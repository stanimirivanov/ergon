package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ActiveApprovalRequestExistsException
import org.ergon.controlplane.resolution.application.ApprovalNotRequiredException
import org.ergon.controlplane.resolution.application.ApprovalRequestNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps approval-request failures to stable RFC 9457 problem documents. */
@RestControllerAdvice(assignableTypes = [ApprovalRequestController::class])
class ApprovalRequestExceptionHandler {
    @ExceptionHandler(ResolutionRunNotFoundException::class)
    fun runNotFound(exception: ResolutionRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(ApprovalRequestNotFoundException::class)
    fun requestNotFound(exception: ApprovalRequestNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "approval-request-not-found", "Approval request not found", exception)

    @ExceptionHandler(ApprovalNotRequiredException::class)
    fun approvalNotRequired(exception: ApprovalNotRequiredException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "approval-not-required", "Human approval not required", exception)

    @ExceptionHandler(ActiveApprovalRequestExistsException::class)
    fun activeRequestExists(exception: ActiveApprovalRequestExistsException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "active-approval-request-exists",
            "Active approval request exists",
            exception,
        ).apply {
            setProperty("approvalRequestId", exception.requestId)
            setProperty("expiresAt", exception.expiresAt)
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
