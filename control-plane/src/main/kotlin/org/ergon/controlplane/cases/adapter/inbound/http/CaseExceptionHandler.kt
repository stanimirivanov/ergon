package org.ergon.controlplane.cases.adapter.inbound.http

import org.ergon.controlplane.cases.application.CaseNotFoundException
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.contracts.application.ContractRevisionNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps stable application failures to RFC 9457 problem documents without leaking adapter errors. */
@RestControllerAdvice(
    assignableTypes =
        [
            CaseController::class,
            ConnectorObservationController::class,
            CaseAccountAccessFactController::class,
            AccountAccessStateBindingController::class,
            CaseResolutionContractController::class,
            CaseResolutionReadinessController::class,
        ],
)
class CaseExceptionHandler {
    @ExceptionHandler(CaseNotFoundException::class)
    fun notFound(exception: CaseNotFoundException): ProblemDetail =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "urn:ergon:problem:case-not-found",
            title = "Case not found",
            detail = exception.message.orEmpty(),
        )

    @ExceptionHandler(ContractRevisionNotFoundException::class)
    fun contractNotFound(exception: ContractRevisionNotFoundException): ProblemDetail =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "urn:ergon:problem:contract-revision-not-found",
            title = "Contract revision not found",
            detail = exception.message.orEmpty(),
        )

    @ExceptionHandler(ConcurrentCaseModificationException::class)
    fun staleVersion(exception: ConcurrentCaseModificationException): ProblemDetail =
        problem(
            status = HttpStatus.PRECONDITION_FAILED,
            type = "urn:ergon:problem:stale-case-version",
            title = "Stale case version",
            detail = exception.message.orEmpty(),
        ).apply {
            setProperty("expectedVersion", exception.expectedVersion)
            setProperty("actualVersion", exception.actualVersion)
        }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingPrecondition(exception: MissingRequestHeaderException): ProblemDetail =
        problem(
            status = HttpStatus.PRECONDITION_REQUIRED,
            type = "urn:ergon:problem:missing-precondition",
            title = "Missing request precondition",
            detail = exception.message.orEmpty(),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidRequest(exception: MethodArgumentNotValidException): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "urn:ergon:problem:invalid-request",
            title = "Invalid request",
            detail = "Request validation failed",
        ).apply {
            setProperty(
                "violations",
                exception.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" },
            )
        }

    @ExceptionHandler(InvalidVersionPreconditionException::class, IllegalArgumentException::class)
    fun invalidCommand(exception: RuntimeException): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "urn:ergon:problem:invalid-case-command",
            title = "Invalid case command",
            detail = exception.message.orEmpty(),
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
