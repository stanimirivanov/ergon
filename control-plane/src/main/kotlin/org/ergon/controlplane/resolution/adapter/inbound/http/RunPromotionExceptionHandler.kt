package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionRunPromotionRequiredException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Shared refusal for initial, internal retry and browser retry admission; no private evidence is disclosed. */
@RestControllerAdvice(
    assignableTypes = [
        ResolutionRunController::class, ResolutionRunRetryController::class, BrowserRunRetryController::class,
    ],
)
class RunPromotionExceptionHandler {
    @ExceptionHandler(ResolutionRunPromotionRequiredException::class)
    fun required(error: ResolutionRunPromotionRequiredException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(
            ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.message.orEmpty()).apply {
                type = URI.create("urn:ergon:problem:resolution-run-promotion-required")
                title = "Current contract promotion required"
            },
        )
}
