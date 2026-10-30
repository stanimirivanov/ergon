package org.ergon.controlplane.contracts.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.contracts.adapter.inbound.document.PromotionDocumentCodec
import org.ergon.controlplane.contracts.application.ContractPromotions
import org.ergon.controlplane.contracts.application.PromotionAuthorityRepository
import org.ergon.controlplane.contracts.application.PromotionConflict
import org.ergon.controlplane.contracts.application.PromotionIneligible
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.ergon.controlplane.evaluation.application.GateDocumentUnavailable
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
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

/** Trusted internal API. Actor headers identify intent; dedicated stored authority admits mutations. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenant}/contract-promotions")
class ContractPromotionController(
    private val promotions: ContractPromotions,
    private val authorities: PromotionAuthorityRepository,
    private val codec: PromotionDocumentCodec,
) {
    @PostMapping("/authorities/{id}")
    fun attest(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val result = authorities.attest(tenant, id, codec.authority(bytes(request)))
        return response(result.created, result.authority)
    }

    @GetMapping("/authorities/{id}")
    fun authority(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<*> = response(false, authorities.find(tenant, id) ?: throw PromotionUnavailable())

    @PostMapping("/requirements/{key}/commands/{id}")
    fun bind(
        @PathVariable tenant: UUID,
        @PathVariable key: String,
        @PathVariable id: UUID,
        @RequestHeader("X-Ergon-Actor-Id") actor: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val result = promotions.bind(tenant, key, id, actor, codec.binding(bytes(request)))
        return response(result.created, result.binding)
    }

    @GetMapping("/requirements/{key}")
    fun current(
        @PathVariable tenant: UUID,
        @PathVariable key: String,
    ): ResponseEntity<*> = response(false, promotions.current(tenant, key))

    @PostMapping("/commands/{id}")
    fun promote(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
        @RequestHeader("X-Ergon-Actor-Id") actor: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val result = promotions.promote(tenant, id, actor, codec.promotion(bytes(request)))
        return response(result.created, result.receipt)
    }

    @GetMapping("/commands/{id}")
    fun get(
        @PathVariable tenant: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<*> = response(false, promotions.get(tenant, id))

    private fun response(
        created: Boolean,
        value: Any,
    ): ResponseEntity<*> =
        ResponseEntity
            .status(if (created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(value)

    private fun bytes(request: HttpServletRequest): ByteArray {
        val bytes = request.inputStream.readNBytes(PromotionDocumentCodec.MAX_BYTES + 1)
        if (bytes.size > PromotionDocumentCodec.MAX_BYTES) throw SimulationFixtureTooLarge()
        return bytes
    }

    @ExceptionHandler(
        org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario::class,
        org.ergon.controlplane.evaluation.application.InvalidSimulationFixture::class,
        PromotionUnavailable::class,
        PromotionConflict::class,
        PromotionIneligible::class,
        GateDocumentUnavailable::class,
        IllegalArgumentException::class,
        SimulationFixtureTooLarge::class,
        tools.jackson.core.JacksonException::class,
        java.nio.charset.CharacterCodingException::class,
        java.time.DateTimeException::class,
    )
    fun failure(error: Exception): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is PromotionUnavailable, is GateDocumentUnavailable -> HttpStatus.NOT_FOUND
                    is PromotionConflict -> HttpStatus.CONFLICT
                    is SimulationFixtureTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()
}
