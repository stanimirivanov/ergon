package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

/** Storage/invariant failures never disclose causes or masquerade as a missing evidence. */
@RestControllerAdvice(assignableTypes = [BrowserRunPinnedReadinessController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class BrowserRunPinnedReadinessExceptionHandler {
    @ExceptionHandler(InvalidBrowserOidcIdentityException::class)
    fun invalidIdentity(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.FORBIDDEN, "invalid-authenticated-human-identity", "Identity rejected")

    @ExceptionHandler(DataAccessException::class, IllegalStateException::class)
    fun unavailable(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "run-pinned-readiness-unavailable", "Pinned readiness is unavailable")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalid(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "invalid-run-pinned-readiness", "Invalid pinned readiness coordinates")

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                this.type = URI.create("urn:ergon:problem:$type")
                title = detail
            },
        )
}
