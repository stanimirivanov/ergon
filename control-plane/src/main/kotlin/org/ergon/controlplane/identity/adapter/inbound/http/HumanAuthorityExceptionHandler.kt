package org.ergon.controlplane.identity.adapter.inbound.http

import org.ergon.controlplane.identity.application.ApprovalAuthorityEvidenceNotFoundException
import org.ergon.controlplane.identity.application.AuthorityEvidenceCaseNotFoundException
import org.ergon.controlplane.identity.application.HumanActorIdentityAlreadyExistsException
import org.ergon.controlplane.identity.application.HumanActorNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps human-authority use-case failures to stable RFC 9457 problem documents. */
@RestControllerAdvice(assignableTypes = [HumanAuthorityController::class])
class HumanAuthorityExceptionHandler {
    @ExceptionHandler(HumanActorNotFoundException::class)
    fun actorNotFound(exception: HumanActorNotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "urn:ergon:problem:human-actor-not-found",
            "Human actor not found",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(ApprovalAuthorityEvidenceNotFoundException::class)
    fun evidenceNotFound(exception: ApprovalAuthorityEvidenceNotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "urn:ergon:problem:approval-authority-evidence-not-found",
            "Approval authority evidence not found",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(AuthorityEvidenceCaseNotFoundException::class)
    fun caseNotFound(exception: AuthorityEvidenceCaseNotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "urn:ergon:problem:case-not-found",
            "Case not found",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(HumanActorIdentityAlreadyExistsException::class)
    fun actorExists(exception: HumanActorIdentityAlreadyExistsException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "urn:ergon:problem:human-actor-identity-exists",
            "Human actor identity already exists",
            exception.message.orEmpty(),
        ).apply { setProperty("actorId", exception.actorId) }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidRequest(exception: MethodArgumentNotValidException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "urn:ergon:problem:invalid-human-authority-request",
            "Invalid human authority request",
            "Request validation failed",
        ).apply {
            setProperty("violations", exception.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" })
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest(exception: IllegalArgumentException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "urn:ergon:problem:invalid-human-authority-request",
            "Invalid human authority request",
            exception.message.orEmpty(),
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
        }
}
