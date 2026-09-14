package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ApprovalDecisionNotApprovedException
import org.ergon.controlplane.resolution.application.ApprovalDecisionNotFoundException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantAlreadyExistsException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationWindowExpiredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps capability-authorization derivation failures to stable RFC 9457 documents. */
@RestControllerAdvice(assignableTypes = [CapabilityAuthorizationGrantController::class])
class CapabilityAuthorizationGrantExceptionHandler {
    @ExceptionHandler(ApprovalDecisionNotFoundException::class)
    fun decisionNotFound(exception: ApprovalDecisionNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "approval-decision-not-found", "Approval decision not found", exception)

    @ExceptionHandler(ApprovalDecisionNotApprovedException::class)
    fun decisionNotApproved(exception: ApprovalDecisionNotApprovedException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "approval-decision-not-approved", "Approval decision not approved", exception)

    @ExceptionHandler(CapabilityAuthorizationWindowExpiredException::class)
    fun windowExpired(exception: CapabilityAuthorizationWindowExpiredException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "capability-authorization-window-expired",
            "Capability authorization window expired",
            exception,
        )

    @ExceptionHandler(CapabilityAuthorizationGrantAlreadyExistsException::class)
    fun grantExists(exception: CapabilityAuthorizationGrantAlreadyExistsException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "capability-authorization-grant-already-exists",
            "Capability authorization grant already exists",
            exception,
        ).apply { setProperty("authorizationGrantId", exception.grantId) }

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
