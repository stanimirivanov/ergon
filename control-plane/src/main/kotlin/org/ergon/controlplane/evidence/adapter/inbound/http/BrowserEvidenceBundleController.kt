package org.ergon.controlplane.evidence.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evidence.adapter.serialization.BundleManifestDefinition
import org.ergon.controlplane.evidence.adapter.serialization.EvidenceBundleCodec
import org.ergon.controlplane.evidence.application.EvidenceBundleConflict
import org.ergon.controlplane.evidence.application.EvidenceBundleService
import org.ergon.controlplane.evidence.application.InvalidEvidenceBundle
import org.ergon.controlplane.evidence.application.StoredEvidenceBundle
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.identity.domain.TenantId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import java.time.Instant
import java.util.UUID

/** Explicit publication of exact documentary selections; no contract mutation, promotion or execution authority. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/evidence-bundles/{id}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserEvidenceBundleController(
    private val actors: AuthenticatedHumanActorResolver,
    private val bundles: EvidenceBundleService,
    private val codec: EvidenceBundleCodec,
    mapper: JsonMapper,
) {
    private val reader =
        mapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Textual) { config ->
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            }.build()
            .readerFor(BundlePublicationInput::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @PostMapping(consumes = ["application/json"])
    fun publish(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<BrowserEvidenceBundle> {
        val tenant = TenantId(identity(path, "tenantId"))
        val actor = actors.resolve(tenant.value, principal).actor.id
        val input = decode(request)
        val command =
            try {
                EvidenceBundleCommand(
                    identity(path, "id"),
                    input.claims.map { requireNotNull(it) },
                    input.procedures.map { requireNotNull(it) },
                    input.claimSets.map {
                        val group = requireNotNull(it)
                        BundleClaimSetSelection(
                            group.anchorId,
                            group.revision,
                            group.candidateIds.map { id -> requireNotNull(id) },
                            group.adjudicationId,
                        )
                    },
                )
            } catch (_: IllegalArgumentException) {
                throw InvalidEvidenceBundle()
            }
        val result = bundles.publish(tenant, actor, command)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(response(tenant, result.stored))
    }

    @GetMapping
    fun get(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserEvidenceBundle> {
        val tenant = TenantId(identity(path, "tenantId"))
        val actor = actors.resolve(tenant.value, principal).actor.id
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(response(tenant, bundles.get(tenant, actor, identity(path, "id"))))
    }

    /** Exact canonical bytes for independent digest checks; the envelope endpoint supplies provenance. */
    @GetMapping("/canonical", produces = ["application/json"])
    fun canonical(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<String> {
        val tenant = TenantId(identity(path, "tenantId"))
        val actor = actors.resolve(tenant.value, principal).actor.id
        val stored = bundles.get(tenant, actor, identity(path, "id"))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType("application", "json", Charsets.UTF_8))
            .eTag("\"" + stored.sha256 + "\"")
            .body(codec.encode(tenant, stored.manifest))
    }

    @ExceptionHandler(
        InvalidEvidenceBundle::class,
        EvidenceBundleConflict::class,
        SourceUnavailable::class,
        SourceTooLarge::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is SourceUnavailable -> HttpStatus.NOT_FOUND
                    is EvidenceBundleConflict -> HttpStatus.CONFLICT
                    is SourceTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun decode(request: HttpServletRequest): BundlePublicationInput {
        val bytes = request.inputStream.readNBytes(MAX_COMMAND_BYTES + 1)
        if (bytes.size > MAX_COMMAND_BYTES) throw SourceTooLarge()
        return try {
            reader.readValue<BundlePublicationInput>(bytes)
        } catch (_: JacksonException) {
            throw InvalidEvidenceBundle()
        }
    }

    private fun identity(
        path: Map<String, String>,
        key: String,
    ): UUID =
        try {
            UUID.fromString(path.getValue(key))
        } catch (_: IllegalArgumentException) {
            throw InvalidEvidenceBundle()
        }

    private fun response(
        tenant: TenantId,
        bundle: StoredEvidenceBundle,
    ) = BrowserEvidenceBundle(
        bundle.command.id,
        bundle.sha256,
        bundle.publisher.value,
        bundle.authority.value,
        bundle.recordedAt,
        codec.definition(tenant, bundle.manifest),
    )

    companion object {
        const val MAX_COMMAND_BYTES = 32_768
    }
}

data class BundlePublicationInput(
    @param:JsonProperty(required = true) val claims: List<UUID?>,
    @param:JsonProperty(required = true) val procedures: List<UUID?>,
    @param:JsonProperty(required = true) val claimSets: List<BundleGroupInput?>,
)

data class BundleGroupInput(
    @param:JsonProperty(required = true) val anchorId: UUID,
    @param:JsonProperty(required = true) val revision: Long,
    @param:JsonProperty(required = true) val candidateIds: List<UUID?>,
    val adjudicationId: UUID? = null,
)

data class BrowserEvidenceBundle(
    val id: UUID,
    val sha256: String,
    val publishedBy: UUID,
    val authorityEvidenceId: UUID,
    val recordedAt: Instant,
    val manifest: BundleManifestDefinition,
)
