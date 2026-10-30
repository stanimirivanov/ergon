package org.ergon.controlplane.evaluation.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evaluation.adapter.inbound.document.GateDocumentJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.application.EligibilityRequest
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentConflict
import org.ergon.controlplane.evaluation.application.GateDocumentIdentity
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.GateDocumentUnavailable
import org.ergon.controlplane.evaluation.application.InvalidGateDocument
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.ergon.evaluation.domain.SimulationArtifact
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
import java.util.UUID

/** Trusted internal machine routes; no public/browser authorization or promotion command is implied. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenant}/evaluation-gates")
class EvaluationSuiteController(
    private val suites: EvaluationSuites,
    private val eligibility: EvaluatePromotionEligibility,
    private val strict: PinnedScenarioJsonDecoder,
) {
    @Suppress("LongParameterList") // Explicit HTTP path, attribution and bounded body bindings.
    @PostMapping("/{kind}/{key}/revisions/{revision}")
    fun register(
        @PathVariable tenant: UUID,
        @PathVariable kind: GateDocumentKind,
        @PathVariable key: String,
        @PathVariable revision: String,
        @RequestHeader("X-Ergon-Actor-Id") actor: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val result = suites.register(kind, GateDocumentIdentity(tenant, key, revision), bytes(request), actor)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(result.stored)
    }

    @GetMapping("/{kind}/{key}/revisions/{revision}")
    fun get(
        @PathVariable tenant: UUID,
        @PathVariable kind: GateDocumentKind,
        @PathVariable key: String,
        @PathVariable revision: String,
    ): ResponseEntity<*> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(suites.get(kind, GateDocumentIdentity(tenant, key, revision)))

    @PostMapping("/SUITE/{key}/revisions/{revision}/eligibility")
    fun assess(
        @PathVariable tenant: UUID,
        @PathVariable key: String,
        @PathVariable revision: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val body =
            Charsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes(request)))
                .toString()
        val wire = strict.read(body, EligibilityRequestDocument::class.java, "ergon.simulation/eligibility-request/v1")
        val result =
            eligibility.assess(
                GateDocumentIdentity(tenant, key, revision),
                EligibilityRequest(wire.contract, wire.policy, wire.reports),
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }

    private fun bytes(request: HttpServletRequest): ByteArray {
        val bytes = request.inputStream.readNBytes(GateDocumentJsonCodec.MAX_BYTES + 1)
        if (bytes.size > GateDocumentJsonCodec.MAX_BYTES) throw SimulationFixtureTooLarge()
        return bytes
    }

    @ExceptionHandler(
        GateDocumentConflict::class,
        GateDocumentUnavailable::class,
        InvalidGateDocument::class,
        InvalidSimulationFixture::class,
        SimulationFixtureTooLarge::class,
        UnsupportedPinnedScenario::class,
        IllegalArgumentException::class,
        tools.jackson.core.JacksonException::class,
        java.nio.charset.CharacterCodingException::class,
    )
    fun failure(error: Exception): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is GateDocumentConflict -> HttpStatus.CONFLICT
                    is GateDocumentUnavailable -> HttpStatus.NOT_FOUND
                    is SimulationFixtureTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()
}

data class EligibilityRequestDocument(
    val schemaVersion: String,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val reports: List<SelectedEvaluationReport>,
)
