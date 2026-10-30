package org.ergon.controlplane.evaluation.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationExecution
import org.ergon.controlplane.evaluation.application.EvaluationReportConflict
import org.ergon.controlplane.evaluation.application.EvaluationReportUnavailable
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.StoredEvaluationReport
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/** Trusted-machine boundary only; caller supplies a fixture reference, never report content or a pass flag. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}")
class EvaluationReportController(
    private val evaluations: EvaluationExecution,
) {
    // Each parameter binds a distinct HTTP path/header/body boundary.
    @Suppress("LongParameterList")
    @PostMapping("/simulation-fixtures/{key}/revisions/{revision}/evaluations/{id}")
    fun execute(
        @PathVariable tenantId: UUID,
        @PathVariable key: String,
        @PathVariable revision: String,
        @PathVariable id: UUID,
        @RequestHeader("X-Ergon-Actor-Id") actor: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<StoredEvaluationReport> {
        if (request.inputStream.read() != -1) throw InvalidSimulationFixture()
        val identity =
            try {
                SimulationFixtureIdentity(tenantId, key, revision)
            } catch (
                _: IllegalArgumentException,
            ) {
                throw InvalidSimulationFixture()
            }
        val result = evaluations.execute(EvaluationCommand(identity, id), actor)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .location(URI.create("/internal/v1/tenants/$tenantId/simulation-evaluations/$id"))
            .cacheControl(CacheControl.noStore())
            .body(result.stored)
    }

    @GetMapping("/simulation-evaluations/{id}")
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<StoredEvaluationReport> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(evaluations.get(tenantId, id))

    @ExceptionHandler(
        EvaluationReportConflict::class,
        EvaluationReportUnavailable::class,
        InvalidSimulationFixture::class,
        SimulationFixtureTooLarge::class,
        UnsupportedPinnedScenario::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is EvaluationReportConflict -> HttpStatus.CONFLICT
                    is EvaluationReportUnavailable -> HttpStatus.NOT_FOUND
                    is SimulationFixtureTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()
}
