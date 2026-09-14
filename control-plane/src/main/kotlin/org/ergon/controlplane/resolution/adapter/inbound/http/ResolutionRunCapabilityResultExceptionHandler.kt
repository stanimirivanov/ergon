package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityReceiptNotFoundException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps capability-result recording failures to stable RFC 9457 documents. */
@RestControllerAdvice(assignableTypes = [ResolutionRunCapabilityResultController::class])
class ResolutionRunCapabilityResultExceptionHandler {
    @ExceptionHandler(ResolutionRunNotFoundException::class)
    fun runNotFound(exception: ResolutionRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(ResolutionRunCapabilityReceiptNotFoundException::class)
    fun receiptNotFound(exception: ResolutionRunCapabilityReceiptNotFoundException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "resolution-run-capability-receipt-not-found",
            "Resolution run capability receipt not found",
            exception,
        )

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
