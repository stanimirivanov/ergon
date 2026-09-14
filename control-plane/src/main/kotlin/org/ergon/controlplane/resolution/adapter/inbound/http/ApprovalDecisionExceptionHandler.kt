package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.ergon.controlplane.resolution.application.ApprovalDecisionAlreadyExistsException
import org.ergon.controlplane.resolution.application.ApprovalRequestExpiredException
import org.ergon.controlplane.resolution.application.ApprovalRequestNotFoundException
import org.ergon.controlplane.resolution.application.CurrentApprovalAuthorityNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps authenticated approval-decision failures to stable RFC 9457 documents. */
@RestControllerAdvice(assignableTypes = [ApprovalDecisionController::class])
class ApprovalDecisionExceptionHandler {
    @ExceptionHandler(ApprovalRequestNotFoundException::class)
    fun requestNotFound(exception: ApprovalRequestNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "approval-request-not-found", "Approval request not found", exception)

    @ExceptionHandler(ApprovalRequestExpiredException::class)
    fun requestExpired(exception: ApprovalRequestExpiredException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "approval-request-expired", "Approval request expired", exception)

    @ExceptionHandler(CurrentApprovalAuthorityNotFoundException::class)
    fun authorityNotFound(exception: CurrentApprovalAuthorityNotFoundException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "current-approval-authority-not-found", "Current authority not found", exception)

    @ExceptionHandler(ApprovalDecisionAlreadyExistsException::class)
    fun decisionExists(exception: ApprovalDecisionAlreadyExistsException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "approval-decision-already-exists", "Approval decision already exists", exception)
            .apply { setProperty("approvalDecisionId", exception.decisionId) }

    @ExceptionHandler(
        AuthenticatedHumanActorNotRegisteredException::class,
        UntrustedHumanIdentityIssuerException::class,
        InvalidAuthenticatedHumanIdentityException::class,
    )
    fun invalidActor(exception: RuntimeException): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "invalid-authenticated-human-actor",
            "Invalid authenticated human actor",
            exception,
        )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
    )
    fun invalidRequest(exception: Exception): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid-approval-decision", "Invalid approval decision", exception)

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
