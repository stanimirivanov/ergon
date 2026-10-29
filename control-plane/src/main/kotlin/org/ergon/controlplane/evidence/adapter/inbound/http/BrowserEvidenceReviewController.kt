package org.ergon.controlplane.evidence.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evidence.application.EvidenceReviewConflict
import org.ergon.controlplane.evidence.application.EvidenceReviewCursor
import org.ergon.controlplane.evidence.application.EvidenceReviewPage
import org.ergon.controlplane.evidence.application.EvidenceReviewReceipt
import org.ergon.controlplane.evidence.application.EvidenceReviewService
import org.ergon.controlplane.evidence.application.InvalidEvidenceReview
import org.ergon.controlplane.evidence.application.ReviewableEvidenceProposal
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

/** Browser-session and CSRF protected documentary review; decisions never grant execution authority. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/evidence-proposals")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserEvidenceReviewController(
    private val actors: AuthenticatedHumanActorResolver,
    private val reviews: EvidenceReviewService,
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
            .readerFor(EvidenceReviewInput::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @PostMapping("/{kind}/{id}/reviews/{commandId}", consumes = ["application/json"])
    fun decide(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<BrowserEvidenceReviewReceipt> {
        val tenant = tenant(path)
        val actor = actors.resolve(tenant.value, principal).actor.id
        val input = decode(request)
        val command =
            invalidInput {
                EvidenceReviewCommand(
                    UUID.fromString(path.getValue("commandId")),
                    input.extractionId,
                    input.expectedVersion,
                    EvidenceReviewDecision.valueOf(input.decision),
                    input.rationale,
                )
            }
        val result = reviews.decide(tenant, actor, target(path), command)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(result.receipt.response())
    }

    @GetMapping("/{kind}/{id}/review")
    fun inspect(
        @PathVariable path: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserEvidenceInspection> {
        val tenant = tenant(path)
        val actor = actors.resolve(tenant.value, principal).actor.id
        val result = reviews.inspect(tenant, actor, target(path))
        val status =
            result.receipt
                ?.command
                ?.decision
                ?.name ?: "PROPOSED"
        val claim = (result.proposal as? ReviewableEvidenceProposal.Claim)?.stored?.response()?.copy(status = status)
        val procedure =
            (result.proposal as? ReviewableEvidenceProposal.Procedure)?.stored?.response()?.copy(status = status)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            BrowserEvidenceInspection(
                result.proposal.target,
                status,
                if (result.receipt == null) 0 else 1,
                claim,
                procedure,
                result.receipt?.response(),
            ),
        )
    }

    @GetMapping("/pending")
    fun pending(
        @PathVariable path: Map<String, String>,
        @RequestParam query: Map<String, String>,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserEvidenceReviewPage> {
        val tenant = tenant(path)
        val actor = actors.resolve(tenant.value, principal).actor.id
        val (before, limit) =
            invalidInput {
                require(query.keys.all { it in QUERY_KEYS })
                val parts = CURSOR_KEYS.map { query[it] }
                val cursor =
                    if (parts.all { it == null }) {
                        null
                    } else {
                        require(parts.all { it != null })
                        EvidenceReviewCursor(
                            Instant.parse(parts[0]),
                            EvidenceProposalKind.valueOf(checkNotNull(parts[1])),
                            UUID.fromString(parts[2]),
                        )
                    }
                cursor to (query["limit"]?.toInt() ?: DEFAULT_LIMIT)
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(reviews.pending(tenant, actor, before, limit).response())
    }

    @ExceptionHandler(
        InvalidEvidenceReview::class,
        SourceUnavailable::class,
        SourceTooLarge::class,
        EvidenceReviewConflict::class,
    )
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is SourceUnavailable -> HttpStatus.NOT_FOUND
                    is EvidenceReviewConflict -> HttpStatus.CONFLICT
                    is SourceTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun decode(request: HttpServletRequest): EvidenceReviewInput {
        val bytes = request.inputStream.readNBytes(MAX_COMMAND_BYTES + 1)
        if (bytes.size > MAX_COMMAND_BYTES) throw SourceTooLarge()
        return try {
            reader.readValue<EvidenceReviewInput>(bytes)
        } catch (_: JacksonException) {
            throw InvalidEvidenceReview()
        }
    }

    private fun tenant(path: Map<String, String>): TenantId =
        invalidInput {
            TenantId(
                UUID.fromString(path.getValue("tenantId")),
            )
        }

    private fun target(path: Map<String, String>): EvidenceReviewTarget =
        invalidInput {
            EvidenceReviewTarget(
                EvidenceProposalKind.valueOf(path.getValue("kind")),
                UUID.fromString(path.getValue("id")),
            )
        }

    private fun <T> invalidInput(block: () -> T): T =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            throw InvalidEvidenceReview()
        } catch (_: DateTimeException) {
            throw InvalidEvidenceReview()
        }

    companion object {
        const val MAX_COMMAND_BYTES = 16_384
        private const val DEFAULT_LIMIT = 20
        private val CURSOR_KEYS = listOf("beforeRecordedAt", "beforeKind", "beforeId")
        private val QUERY_KEYS = CURSOR_KEYS + "limit"
    }
}

data class EvidenceReviewInput(
    @param:JsonProperty(required = true) val extractionId: UUID,
    @param:JsonProperty(required = true) val expectedVersion: Long,
    @param:JsonProperty(required = true) val decision: String,
    @param:JsonProperty(required = true) val rationale: String,
)

data class BrowserEvidenceReviewReceipt(
    val target: EvidenceReviewTarget,
    val commandId: UUID,
    val extractionId: UUID,
    val version: Long,
    val decision: String,
    val rationale: String,
    val reviewerId: UUID,
    val authorityEvidenceId: UUID,
    val reviewedAt: Instant,
)

data class BrowserEvidenceInspection(
    val target: EvidenceReviewTarget,
    val status: String,
    val version: Long,
    val claim: ClaimProposalResponse?,
    val procedure: BrowserProcedureProposal?,
    val review: BrowserEvidenceReviewReceipt?,
)

private fun EvidenceReviewReceipt.response(): BrowserEvidenceReviewReceipt =
    BrowserEvidenceReviewReceipt(
        target,
        command.id,
        command.extractionId,
        EvidenceReviewCommand.DECIDED_VERSION,
        command.decision.name,
        command.rationale,
        reviewer.value,
        authority.value,
        recordedAt,
    )

// Wire coordinates match the query names; no tenant or domain value-class serialization leaks into this contract.
data class BrowserReviewCursor(
    val beforeRecordedAt: Instant,
    val beforeKind: String,
    val beforeId: UUID,
)

data class BrowserPendingProposal(
    val kind: String,
    val id: UUID,
    val sourceId: UUID,
    val digest: String,
    val extractionId: UUID,
    val compilerRevision: String,
    val proposedBy: UUID,
    val recordedAt: Instant,
    val label: String,
    val status: String = "PROPOSED",
    val version: Long = 0,
)

data class BrowserEvidenceReviewPage(
    val entries: List<BrowserPendingProposal>,
    val next: BrowserReviewCursor?,
)

private fun EvidenceReviewPage.response(): BrowserEvidenceReviewPage =
    BrowserEvidenceReviewPage(
        entries.map {
            BrowserPendingProposal(
                it.target.kind.name,
                it.target.id,
                it.extraction.source.sourceId.value,
                it.extraction.source.digest.value,
                it.extraction.id,
                it.extraction.extractorRevision,
                it.proposedBy.value,
                it.recordedAt,
                it.label,
            )
        },
        next?.let { BrowserReviewCursor(it.recordedAt, it.kind.name, it.id) },
    )
