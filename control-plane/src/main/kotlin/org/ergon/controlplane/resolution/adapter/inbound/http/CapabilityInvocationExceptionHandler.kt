package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionNotFoundException
import org.ergon.controlplane.resolution.application.CapabilityConnectorUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps capability-invocation failures to stable RFC 9457 documents. */
@RestControllerAdvice(assignableTypes = [CapabilityInvocationController::class])
class CapabilityInvocationExceptionHandler {
    @ExceptionHandler(CapabilityAuthorizationConsumptionNotFoundException::class)
    fun consumptionNotFound(exception: CapabilityAuthorizationConsumptionNotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "capability-authorization-consumption-not-found",
            "Authorization consumption not found",
            exception,
        )

    @ExceptionHandler(CapabilityConnectorUnavailableException::class)
    fun connectorUnavailable(exception: CapabilityConnectorUnavailableException): ProblemDetail =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "capability-connector-unavailable",
            "Capability connector unavailable",
            exception,
        ).apply { setProperty("connector", exception.connector) }

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
