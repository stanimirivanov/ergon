package org.ergon.controlplane.contracts.adapter.inbound.http

import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.controlplane.contracts.application.ContractDocumentViolation
import org.ergon.controlplane.contracts.application.ContractRevisionAlreadyExistsException
import org.ergon.controlplane.contracts.application.ContractRevisionNotFoundException
import org.ergon.controlplane.contracts.application.InvalidResolutionContractDocumentException
import org.ergon.controlplane.contracts.application.RESOLUTION_CONTRACT_DOCUMENT_SCHEMA
import org.ergon.controlplane.contracts.application.ResolutionContractValidationService
import org.ergon.controlplane.contracts.application.UnregisteredContractReferencesException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Internal adapter for validating a contract document before review or persistence. */
@RestController
@RequestMapping("/internal/v1/resolution-contracts")
class ResolutionContractValidationController(
    private val validationService: ResolutionContractValidationService,
) {
    @PostMapping(
        "/validate",
        consumes = ["application/yaml", "text/yaml"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun validate(
        @RequestBody document: String,
    ): ValidatedResolutionContractResponse = validationService.validate(document).toResponse()
}

data class ValidatedResolutionContractResponse(
    val schema: String,
    val key: String,
    val revision: Int,
    val applicability: FactConditionResponse,
    val requiredEvidence: List<String>,
    val steps: List<ResolutionStepResponse>,
    val outcomeProof: FactConditionResponse,
)

data class FactConditionResponse(
    val fact: String,
    val equals: String,
)

data class ResolutionStepResponse(
    val id: String,
    val capability: String,
    val risk: String,
    val approval: String,
)

/** Maps contract application failures to stable problem documents. */
@RestControllerAdvice(
    assignableTypes =
        [
            ResolutionContractValidationController::class,
            ResolutionContractRevisionController::class,
        ],
)
class ResolutionContractExceptionHandler {
    @ExceptionHandler(InvalidResolutionContractDocumentException::class)
    fun invalidDocument(exception: InvalidResolutionContractDocumentException): ResponseEntity<ProblemDetail> {
        val problem =
            ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "The resolution contract document is invalid.",
            )
        problem.type = URI.create("urn:ergon:problem:invalid-resolution-contract")
        problem.title = "Invalid resolution contract"
        problem.setProperty("violations", exception.violations.map(ContractDocumentViolation::toResponse))
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem)
    }

    @ExceptionHandler(ContractRevisionAlreadyExistsException::class)
    fun revisionExists(exception: ContractRevisionAlreadyExistsException): ProblemDetail =
        problem(
            status = HttpStatus.CONFLICT,
            type = "urn:ergon:problem:contract-revision-already-exists",
            title = "Contract revision already exists",
            detail = exception.message.orEmpty(),
        )

    @ExceptionHandler(UnregisteredContractReferencesException::class)
    fun unregisteredReferences(exception: UnregisteredContractReferencesException): ResponseEntity<ProblemDetail> {
        val problem =
            ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "The resolution contract refers to unregistered semantic names.",
            )
        problem.type = URI.create("urn:ergon:problem:unregistered-contract-references")
        problem.title = "Unregistered contract references"
        problem.setProperty("violations", exception.violations.map(ContractDocumentViolation::toResponse))
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem)
    }

    @ExceptionHandler(ContractRevisionNotFoundException::class)
    fun revisionNotFound(exception: ContractRevisionNotFoundException): ProblemDetail =
        problem(
            status = HttpStatus.NOT_FOUND,
            type = "urn:ergon:problem:contract-revision-not-found",
            title = "Contract revision not found",
            detail = exception.message.orEmpty(),
        )

    @ExceptionHandler(InvalidContractRevisionIdentityException::class)
    fun invalidRevisionIdentity(exception: InvalidContractRevisionIdentityException): ProblemDetail =
        problem(
            status = HttpStatus.BAD_REQUEST,
            type = "urn:ergon:problem:invalid-contract-revision-identity",
            title = "Invalid contract revision identity",
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

data class ContractDocumentViolationResponse(
    val path: String,
    val message: String,
)

private fun ContractDocumentViolation.toResponse() = ContractDocumentViolationResponse(path, message)

internal fun ResolutionContract.toResponse() =
    ValidatedResolutionContractResponse(
        schema = RESOLUTION_CONTRACT_DOCUMENT_SCHEMA,
        key = key.value,
        revision = revision.value,
        applicability = applicability.toResponse(),
        requiredEvidence = requiredEvidence.map { it.value },
        steps = steps.map(ResolutionStep::toResponse),
        outcomeProof = outcomeProof.toResponse(),
    )

private fun FactCondition.toResponse() = FactConditionResponse(fact.value, expectedValue.value)

private fun ResolutionStep.toResponse() =
    ResolutionStepResponse(
        id = id.value,
        capability = capability.value,
        risk = risk.name,
        approval = approval.name,
    )
