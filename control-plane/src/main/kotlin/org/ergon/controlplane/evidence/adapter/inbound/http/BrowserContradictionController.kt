package org.ergon.controlplane.evidence.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evidence.application.AssessedClaimSet
import org.ergon.controlplane.evidence.application.ContradictionAdjudicationView
import org.ergon.controlplane.evidence.application.ContradictionConflict
import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.evidence.application.ContradictionSetTooLarge
import org.ergon.controlplane.evidence.application.InvalidContradiction
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.DocumentaryContradictions
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

/** Explicit documentary preference/defer only; verified accepted candidates never become execution authority. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/documentary-contradictions")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserContradictionController(
    private val actors: AuthenticatedHumanActorResolver,
    private val contradictions: ContradictionService,
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
            .readerFor(ContradictionInput::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @GetMapping("/claims/{anchorId}")
    fun assess(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserContradictionAssessment> {
        val tenant = tenant(path)
        val actor = actors.resolve(tenant.value, principal).actor.id
        val result = contradictions.assess(tenant, actor, identity(path, "anchorId"))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response())
    }

    @PostMapping("/adjudications/{id}", consumes = ["application/json"])
    fun adjudicate(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<BrowserContradictionAdjudication> {
        val tenant = tenant(path)
        val actor = actors.resolve(tenant.value, principal).actor.id
        val input = decode(request)
        val command =
            invalidInput {
                ContradictionCommand(
                    identity(path, "id"),
                    input.anchorId,
                    input.expectedRevision,
                    input.candidateIds.map { it ?: throw InvalidContradiction() },
                    ContradictionChoice.valueOf(input.choice),
                    input.selectedClaimId,
                    input.rationale,
                )
            }
        val result = contradictions.adjudicate(tenant, actor, command)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(result.view.response())
    }

    @GetMapping("/adjudications/{id}")
    fun get(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserContradictionAdjudication> {
        val tenant = tenant(path)
        val actor = actors.resolve(tenant.value, principal).actor.id
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(contradictions.get(tenant, actor, identity(path, "id")).response())
    }

    @ExceptionHandler(
        InvalidContradiction::class,
        ContradictionConflict::class,
        ContradictionSetTooLarge::class,
        SourceUnavailable::class,
        SourceTooLarge::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is SourceUnavailable -> HttpStatus.NOT_FOUND
                    is ContradictionConflict -> HttpStatus.CONFLICT
                    is SourceTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun decode(request: HttpServletRequest): ContradictionInput {
        val bytes = request.inputStream.readNBytes(MAX_COMMAND_BYTES + 1)
        if (bytes.size > MAX_COMMAND_BYTES) throw SourceTooLarge()
        return try {
            reader.readValue<ContradictionInput>(bytes)
        } catch (_: JacksonException) {
            throw InvalidContradiction()
        }
    }

    private fun <T> invalidInput(block: () -> T): T =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            throw InvalidContradiction()
        }

    private fun tenant(path: Map<String, String>) = TenantId(identity(path, "tenantId"))

    private fun identity(
        path: Map<String, String>,
        key: String,
    ): UUID =
        try {
            UUID.fromString(path.getValue(key))
        } catch (_: IllegalArgumentException) {
            throw InvalidContradiction()
        }

    companion object {
        const val MAX_COMMAND_BYTES = 16_384
    }
}

data class ContradictionInput(
    @param:JsonProperty(required = true) val anchorId: UUID,
    @param:JsonProperty(required = true) val expectedRevision: Long,
    @param:JsonProperty(required = true) val candidateIds: List<UUID?>,
    @param:JsonProperty(required = true) val choice: String,
    @param:JsonProperty(required = true) val rationale: String,
    val selectedClaimId: UUID? = null,
)

data class BrowserContradictionCandidate(
    val id: UUID,
    val reviewCommandId: UUID,
    val sourceId: UUID,
    val digest: String,
    val claim: ClaimProposalResponse,
)

data class BrowserContradictionFinding(
    val left: UUID,
    val right: UUID,
    val comparison: String,
)

data class BrowserContradictionAssessment(
    val algorithm: String,
    val property: String,
    val subject: String,
    val scope: String,
    val revision: Long,
    val state: String,
    val hasUndetermined: Boolean,
    val candidates: List<BrowserContradictionCandidate>,
    val findings: List<BrowserContradictionFinding>,
)

data class BrowserContradictionAdjudication(
    val id: UUID,
    val anchorId: UUID,
    val choice: String,
    val selectedClaimId: UUID?,
    val rationale: String,
    val reviewerId: UUID,
    val authorityEvidenceId: UUID,
    val adjudicatedAt: Instant,
    val current: Boolean,
    val assessment: BrowserContradictionAssessment,
)

private fun AssessedClaimSet.response(): BrowserContradictionAssessment {
    val codes = findings.map { it.comparison }
    val state =
        when {
            ClaimComparison.CONFLICT in codes -> "CONFLICT"
            ClaimComparison.UNDETERMINED in codes -> "UNDETERMINED"
            findings.isEmpty() -> "INSUFFICIENT_CANDIDATES"
            else -> "NO_DETECTED_CONFLICT"
        }
    return BrowserContradictionAssessment(
        DocumentaryContradictions.ALGORITHM,
        set.key.property.key,
        set.key.subject,
        set.key.scope,
        set.revision,
        state,
        ClaimComparison.UNDETERMINED in codes,
        set.candidates.map {
            val source = it.stored.proposal.extraction.source
            BrowserContradictionCandidate(
                it.stored.proposal.id,
                it.reviewCommandId,
                source.sourceId.value,
                source.digest.value,
                it.stored.response().copy(status = "ACCEPTED"),
            )
        },
        findings.map { BrowserContradictionFinding(it.left, it.right, it.comparison.name) },
    )
}

private fun ContradictionAdjudicationView.response(): BrowserContradictionAdjudication =
    BrowserContradictionAdjudication(
        stored.command.id,
        stored.command.anchorId,
        stored.command.choice.name,
        stored.command.selectedClaimId,
        stored.command.rationale,
        stored.reviewer.value,
        stored.authority.value,
        stored.recordedAt,
        current,
        assessment.response(),
    )
