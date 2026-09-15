package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.ergon.controlplane.resolution.application.CurrentResolutionRecoveryAuthorityNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationStateException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryBudgetAvailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps exhausted-run escalation failures to stable RFC 9457 problem documents. */
@RestControllerAdvice(assignableTypes = [ResolutionRunEscalationController::class])
class ResolutionRunEscalationExceptionHandler {
    @ExceptionHandler(ResolutionRunNotFoundException::class)
    fun runNotFound(exception: ResolutionRunNotFoundException) =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(ResolutionRunEscalationStateException::class)
    fun state(exception: ResolutionRunEscalationStateException) =
        problem(HttpStatus.CONFLICT, "resolution-run-not-escalatable", "Resolution run is not escalatable", exception)
            .apply { setProperty("state", exception.state) }

    @ExceptionHandler(ResolutionRunRetryBudgetAvailableException::class)
    fun retryBudget(exception: ResolutionRunRetryBudgetAvailableException) =
        problem(
            HttpStatus.CONFLICT,
            "resolution-run-retry-budget-available",
            "Resolution run retry budget is available",
            exception,
        ).apply {
            setProperty("policyRevision", exception.policyRevision.value)
            setProperty("sourceAttemptNumber", exception.sourceAttemptNumber)
            setProperty("maximumAttempts", exception.maximumAttempts)
        }

    @ExceptionHandler(
        CurrentResolutionRecoveryAuthorityNotFoundException::class,
        AuthenticatedHumanActorNotRegisteredException::class,
        UntrustedHumanIdentityIssuerException::class,
        InvalidAuthenticatedHumanIdentityException::class,
    )
    fun forbidden(exception: RuntimeException) =
        problem(
            HttpStatus.FORBIDDEN,
            "resolution-run-escalation-forbidden",
            "Resolution run escalation forbidden",
            exception,
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        exception: Exception,
    ) = ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
        this.type = URI.create("urn:ergon:problem:$type")
        this.title = title
    }
}
