package org.ergon.controlplane.evaluation.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureConflict
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureService
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.SimulationFixtureUnavailable
import org.ergon.controlplane.evaluation.application.StoredSimulationFixture
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

/** Trusted-machine API only. Actor headers provide attribution, never browser authentication. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/simulation-fixtures/{key}/revisions/{revision}")
class SimulationFixtureController(
    private val fixtures: SimulationFixtureService,
) {
    @PostMapping(consumes = ["application/json"], produces = ["application/json"])
    fun register(
        @PathVariable tenantId: UUID,
        @PathVariable key: String,
        @PathVariable revision: String,
        @RequestHeader("X-Ergon-Actor-Id") actor: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<StoredSimulationFixture> {
        val identity = identity(tenantId, key, revision)
        val bytes = request.inputStream.readNBytes(SimulationFixtureService.MAX_BYTES + 1)
        val result = fixtures.register(identity, actor, bytes)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .location(URI.create("/internal/v1/tenants/$tenantId/simulation-fixtures/$key/revisions/$revision"))
            .cacheControl(CacheControl.noStore())
            .body(result.stored)
    }

    @GetMapping(produces = ["application/json"])
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable key: String,
        @PathVariable revision: String,
    ): ResponseEntity<StoredSimulationFixture> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(fixtures.get(identity(tenantId, key, revision)))

    @ExceptionHandler(
        InvalidSimulationFixture::class,
        SimulationFixtureTooLarge::class,
        SimulationFixtureConflict::class,
        SimulationFixtureUnavailable::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is SimulationFixtureTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    is SimulationFixtureConflict -> HttpStatus.CONFLICT
                    is SimulationFixtureUnavailable -> HttpStatus.NOT_FOUND
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun identity(
        tenant: UUID,
        key: String,
        revision: String,
    ): SimulationFixtureIdentity =
        try {
            SimulationFixtureIdentity(tenant, key, revision)
        } catch (_: IllegalArgumentException) {
            throw InvalidSimulationFixture()
        }
}
