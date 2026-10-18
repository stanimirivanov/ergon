package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionRunCancelledException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Safe denial shared by legacy approval/authorization adapters and browser decisions. */
@RestControllerAdvice
class CancelledRunWriteExceptionHandler {
    @ExceptionHandler(ResolutionRunCancelledException::class)
    fun cancelled(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .cacheControl(CacheControl.noStore())
            .body(
                ProblemDetail
                    .forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        "Cancelled runs cannot acquire new approval or authorization",
                    ).apply {
                        type = URI.create("urn:ergon:problem:resolution-run-cancelled")
                        title = "Resolution run cancelled"
                    },
            )
}
