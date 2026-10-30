package org.ergon.controlplane.evaluation.adapter.inbound.browser

import org.ergon.controlplane.contracts.application.PromotionConflict
import org.ergon.controlplane.contracts.application.PromotionIneligible
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.ergon.controlplane.evaluation.application.GateDocumentUnavailable
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.SimulationLabConflict
import org.ergon.controlplane.evaluation.application.SimulationLabUnavailable
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

@RestControllerAdvice(
    assignableTypes = [
        SimulationLabController::class,
        SimulationLabAuthorityController::class,
    ],
)
class SimulationLabExceptionHandler {
    @ExceptionHandler(
        SimulationLabUnavailable::class,
        PromotionUnavailable::class,
        GateDocumentUnavailable::class,
        AuthenticatedHumanActorNotRegisteredException::class,
        InvalidBrowserOidcIdentityException::class,
        UntrustedHumanIdentityIssuerException::class,
    )
    fun unavailable() =
        problem(
            HttpStatus.NOT_FOUND,
            "simulation-lab-unavailable",
            "Simulation Lab resource or authority unavailable",
        )

    @ExceptionHandler(SimulationLabConflict::class, PromotionConflict::class, PromotionIneligible::class)
    fun conflict() =
        problem(
            HttpStatus.CONFLICT,
            "simulation-lab-conflict",
            "Review current requirements and exact eligible report evidence",
        )

    @ExceptionHandler(SimulationFixtureTooLarge::class)
    fun tooLarge() =
        problem(
            HttpStatus.CONTENT_TOO_LARGE,
            "simulation-lab-request-too-large",
            "Simulation Lab request exceeds the allowed size",
        )

    @ExceptionHandler(
        IllegalArgumentException::class,
        InvalidSimulationFixture::class,
        UnsupportedPinnedScenario::class,
        tools.jackson.core.JacksonException::class,
        java.nio.charset.CharacterCodingException::class,
        java.time.DateTimeException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid() =
        problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "invalid-simulation-lab-request",
            "Invalid Simulation Lab request",
        )

    @ExceptionHandler(DataAccessException::class, IllegalStateException::class)
    fun failure() =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "simulation-lab-storage-unavailable",
            "Simulation Lab is temporarily unavailable",
        )

    private fun problem(
        status: HttpStatus,
        code: String,
        title: String,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
            ProblemDetail.forStatus(status).apply {
                this.title = title
                type = URI.create("urn:ergon:problem:$code")
            },
        )
}
