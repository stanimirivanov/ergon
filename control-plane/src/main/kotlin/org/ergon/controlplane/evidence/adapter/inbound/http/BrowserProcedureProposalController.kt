package org.ergon.controlplane.evidence.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evidence.application.InvalidProcedure
import org.ergon.controlplane.evidence.application.ProcedureCommand
import org.ergon.controlplane.evidence.application.ProcedureConflict
import org.ergon.controlplane.evidence.application.ProcedureOperationBinding
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.DocumentedOperation
import org.ergon.evidence.domain.ProcedureProposal
import org.ergon.evidence.domain.SourceCitation
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

/** Versioned documentary procedures; this boundary never accepts raw quotations, actor hints or execution commands. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}/procedure-proposals/{id}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserProcedureProposalController(
    private val actors: AuthenticatedHumanActorResolver,
    private val procedures: ProcedureProposalService,
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
            .readerFor(ProcedureInput::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @PostMapping(consumes = ["application/json"])
    fun propose(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<BrowserProcedureProposal> {
        val (source, id) = identity(path)
        val actor = actors.resolve(source.tenantId.value, principal).actor.id
        val input = decode(request)
        val bindings =
            try {
                input.operations.orEmpty().map { selection ->
                    val operation = selection ?: throw InvalidProcedure()
                    ProcedureOperationBinding(
                        operation.position,
                        SourceRevision(source.tenantId, SourceId(operation.sourceId), SourceDigest(operation.digest)),
                        operation.extractionId,
                        operation.observationIndex,
                    )
                }
            } catch (_: IllegalArgumentException) {
                throw InvalidProcedure()
            }
        val command = ProcedureCommand(id, input.extractionId, input.observationIndex, input.title, bindings)
        val result = procedures.propose(source, actor, command)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(result.stored.response())
    }

    @GetMapping
    fun get(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserProcedureProposal> {
        val (source, id) = identity(path)
        val actor = actors.resolve(source.tenantId.value, principal).actor.id
        val stored = procedures.get(source, actor, id)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stored.response())
    }

    @ExceptionHandler(
        InvalidProcedure::class,
        InvalidSource::class,
        SourceUnavailable::class,
        SourceTooLarge::class,
        ProcedureConflict::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is SourceUnavailable -> HttpStatus.NOT_FOUND
                    is ProcedureConflict -> HttpStatus.CONFLICT
                    is SourceTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun decode(request: HttpServletRequest): ProcedureInput {
        val bytes = request.inputStream.readNBytes(MAX_COMMAND_BYTES + 1)
        if (bytes.size > MAX_COMMAND_BYTES) throw SourceTooLarge()
        return try {
            reader.readValue<ProcedureInput>(bytes)
        } catch (_: JacksonException) {
            throw InvalidProcedure()
        }
    }

    private fun identity(path: Map<String, String>): Pair<SourceRevision, UUID> =
        try {
            val source =
                SourceRevision(
                    TenantId(UUID.fromString(path.getValue("tenantId"))),
                    SourceId(UUID.fromString(path.getValue("sourceId"))),
                    SourceDigest(path.getValue("digest")),
                )
            source to UUID.fromString(path.getValue("id"))
        } catch (_: IllegalArgumentException) {
            throw InvalidProcedure()
        }

    companion object {
        const val MAX_COMMAND_BYTES = 16_384
    }
}

data class ProcedureInput(
    @param:JsonProperty(required = true) val extractionId: UUID,
    @param:JsonProperty(required = true) val observationIndex: Int,
    @param:JsonProperty(required = true) val title: String,
    val operations: List<ProcedureOperationInput?>? = null,
)

data class ProcedureOperationInput(
    @param:JsonProperty(required = true) val position: Int,
    @param:JsonProperty(required = true) val sourceId: UUID,
    @param:JsonProperty(required = true) val digest: String,
    @param:JsonProperty(required = true) val extractionId: UUID,
    @param:JsonProperty(required = true) val observationIndex: Int,
)

data class BrowserProcedureCitation(
    val sourceId: UUID,
    val digest: String,
    val start: Int,
    val endExclusive: Int,
    val quote: String,
)

data class BrowserProcedureOperation(
    val extractionId: UUID,
    val compilerRevision: String,
    val observationIndex: Int,
    val jsonPointer: String,
    val citation: BrowserProcedureCitation,
)

data class BrowserProcedureStep(
    val position: Int,
    val citation: BrowserProcedureCitation,
    val operation: BrowserProcedureOperation?,
)

data class BrowserProcedureProposal(
    val id: UUID,
    val title: String,
    val extractionId: UUID,
    val compilerRevision: String,
    val observationIndex: Int,
    val citation: BrowserProcedureCitation,
    val steps: List<BrowserProcedureStep>,
    val proposedBy: UUID,
    val recordedAt: Instant,
    val status: String = "PROPOSED",
    val bindingRule: String = ProcedureProposal.BINDING_RULE,
)

private fun SourceCitation.response(): BrowserProcedureCitation =
    BrowserProcedureCitation(
        revision.sourceId.value,
        revision.digest.value,
        span.start,
        span.endExclusive,
        quote,
    )

private fun DocumentedOperation.response(): BrowserProcedureOperation =
    BrowserProcedureOperation(
        extraction.id,
        extraction.extractorRevision,
        observationIndex,
        jsonPointer,
        citation.response(),
    )

internal fun StoredProcedureProposal.response(): BrowserProcedureProposal =
    BrowserProcedureProposal(
        proposal.id,
        proposal.content.title,
        proposal.extraction.id,
        proposal.extraction.extractorRevision,
        proposal.observationIndex,
        proposal.content.citation.response(),
        proposal.content.steps.map {
            BrowserProcedureStep(it.position, it.citation.response(), it.operation?.response())
        },
        proposedBy.value,
        recordedAt,
    )
