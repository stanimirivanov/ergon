package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.CapabilityAuthorizationAlreadyConsumedException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantExpiredException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantNotFoundException
import org.ergon.controlplane.resolution.application.TenantCapabilityUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps authorization-consumption failures to stable RFC 9457 documents. */
@RestControllerAdvice(assignableTypes = [CapabilityAuthorizationConsumptionController::class])
class CapabilityAuthorizationConsumptionExceptionHandler {
    @ExceptionHandler(CapabilityAuthorizationGrantNotFoundException::class)
    fun grantNotFound(exception: CapabilityAuthorizationGrantNotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "capability-authorization-grant-not-found",
            "Authorization grant not found",
            exception,
        )

    @ExceptionHandler(CapabilityAuthorizationAlreadyConsumedException::class)
    fun alreadyConsumed(exception: CapabilityAuthorizationAlreadyConsumedException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "capability-authorization-already-consumed",
            "Authorization grant already consumed",
            exception,
        ).apply { setProperty("authorizationConsumptionId", exception.consumptionId) }

    @ExceptionHandler(CapabilityAuthorizationGrantExpiredException::class)
    fun grantExpired(exception: CapabilityAuthorizationGrantExpiredException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "capability-authorization-grant-expired",
            "Authorization grant expired",
            exception,
        )

    @ExceptionHandler(TenantCapabilityUnavailableException::class)
    fun capabilityUnavailable(exception: TenantCapabilityUnavailableException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "tenant-capability-unavailable",
            "Tenant capability unavailable",
            exception,
        ).apply { setProperty("capability", exception.capability) }

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
