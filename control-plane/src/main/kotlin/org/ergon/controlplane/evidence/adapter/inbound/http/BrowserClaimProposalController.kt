package org.ergon.controlplane.evidence.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evidence.application.ClaimConflict
import org.ergon.controlplane.evidence.application.ClaimProposalCommand
import org.ergon.controlplane.evidence.application.ClaimProposalService
import org.ergon.controlplane.evidence.application.InvalidClaim
import org.ergon.controlplane.evidence.application.StoredClaimProposal
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
import org.ergon.evidence.domain.SourceDigest
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.TenantId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
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

/** Documentary proposals use existing workbench sessions/CSRF and never accept caller-authored quotations. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}/claim-proposals/{id}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserClaimProposalController(
    private val actors: AuthenticatedHumanActorResolver,
    private val claims: ClaimProposalService,
    mapper: JsonMapper,
) {
    private val reader =
        mapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .withCoercionConfig(LogicalType.Textual) { config ->
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            }.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build()
            .readerFor(ClaimProposalInput::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @PostMapping(consumes = ["application/json"])
    fun propose(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<ClaimProposalResponse> {
        val (source, id) = identity(path)
        val actor = actors.resolve(source.tenantId.value, principal).actor.id
        val input = decode(request)
        val claim = input.claim()
        val command = ClaimProposalCommand(id, input.extractionId, input.observationIndex, claim)
        val result = claims.propose(source, actor, command)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result.stored.response())
    }

    @GetMapping
    fun get(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<ClaimProposalResponse> {
        val (source, id) = identity(path)
        val actor = actors.resolve(source.tenantId.value, principal).actor.id
        val stored = claims.get(source, actor, id)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stored.response())
    }

    @ExceptionHandler(
        ClaimConflict::class,
        InvalidClaim::class,
        InvalidSource::class,
        SourceUnavailable::class,
        SourceTooLarge::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is ClaimConflict -> HttpStatus.CONFLICT
                    is SourceUnavailable -> HttpStatus.NOT_FOUND
                    is SourceTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun decode(request: HttpServletRequest): ClaimProposalInput {
        val bytes = request.inputStream.readNBytes(MAX_COMMAND_BYTES + 1)
        if (bytes.size > MAX_COMMAND_BYTES) throw SourceTooLarge()
        return try {
            reader.readValue<ClaimProposalInput>(bytes)
        } catch (_: JacksonException) {
            throw InvalidClaim()
        }
    }

    companion object {
        const val MAX_COMMAND_BYTES = 8192
    }

    private fun identity(path: Map<String, String>): Pair<SourceRevision, UUID> =
        try {
            val tenant = TenantId(UUID.fromString(path.getValue("tenantId")))
            val source = SourceId(UUID.fromString(path.getValue("sourceId")))
            val revision = SourceRevision(tenant, source, SourceDigest(path.getValue("digest")))
            revision to UUID.fromString(path.getValue("id"))
        } catch (_: IllegalArgumentException) {
            throw InvalidClaim()
        }
}

/** Exactly one typed value is permitted. Missing validity bounds mean unknown validity. */
data class ClaimProposalInput(
    @param:JsonProperty(value = "extractionId", required = true)
    val extractionId: UUID,
    @param:JsonProperty(value = "observationIndex", required = true)
    val observationIndex: Int,
    @param:JsonProperty(value = "property", required = true)
    val property: String,
    @param:JsonProperty(value = "subject", required = true)
    val subject: String,
    @param:JsonProperty(value = "scope", required = true)
    val scope: String,
    val booleanValue: Boolean? = null,
    val textValue: String? = null,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
) {
    fun claim(): DocumentaryClaim =
        try {
            val registered = DocumentaryProperty.entries.singleOrNull { it.key == property } ?: throw InvalidClaim()
            require((booleanValue == null) != (textValue == null))
            val value =
                booleanValue?.let { DocumentaryValue.Support(it) }
                    ?: DocumentaryValue.Requirement(checkNotNull(textValue))
            DocumentaryClaim(registered, value, subject, scope, validFrom, validUntil)
        } catch (_: IllegalArgumentException) {
            throw InvalidClaim()
        }
}

/** Exact immutable command and selected citation; this status is not a review or operational fact. */
data class ClaimProposalResponse(
    val id: UUID,
    val input: ClaimProposalInput,
    val start: Int,
    val endExclusive: Int,
    val quote: String,
    val compilerRevision: String,
    val proposedBy: UUID,
    val recordedAt: Instant,
    val status: String = "PROPOSED",
    val bindingRule: String = ClaimProposal.BINDING_RULE,
)

internal fun StoredClaimProposal.response(): ClaimProposalResponse {
    val p = proposal
    val c = p.claim
    val input =
        ClaimProposalInput(
            p.extraction.id,
            p.observationIndex,
            c.property.key,
            c.subject,
            c.scope,
            (c.value as? DocumentaryValue.Support)?.value,
            (c.value as? DocumentaryValue.Requirement)?.value,
            c.validFrom,
            c.validUntil,
        )
    return ClaimProposalResponse(
        p.id,
        input,
        p.citation.span.start,
        p.citation.span.endExclusive,
        p.citation.quote,
        p.extraction.extractorRevision,
        proposedBy.value,
        recordedAt,
    )
}
