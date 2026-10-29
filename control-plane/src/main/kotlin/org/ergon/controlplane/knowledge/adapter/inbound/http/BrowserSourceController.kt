package org.ergon.controlplane.knowledge.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceConflict
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.evidence.domain.EvidenceSourceFormat
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
import java.time.Instant
import java.util.UUID

/** Raw UTF-8 ingress: bounded reads avoid unbounded message-converter buffering. No references are fetched. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserSourceController(
    private val actors: AuthenticatedHumanActorResolver,
    private val sources: SourceRevisionService,
    private val compilation: SourceCompilationService,
) {
    @PostMapping(consumes = ["text/markdown", "application/vnd.oai.openapi+json"])
    fun register(
        @PathVariable tenantId: UUID,
        @PathVariable sourceId: UUID,
        @PathVariable digest: String,
        @AuthenticationPrincipal principal: OidcUser,
        request: HttpServletRequest,
    ): ResponseEntity<BrowserSourceRevision> {
        val actor = actors.resolve(tenantId, principal).actor.id
        val revision = revision(tenantId, sourceId, digest)
        sources.requireAccess(revision, actor)
        val format =
            if (request.contentType.substringBefore(';').equals("text/markdown", ignoreCase = true)) {
                EvidenceSourceFormat.COMMONMARK
            } else {
                EvidenceSourceFormat.OPENAPI_3_0_JSON
            }
        val bytes = request.inputStream.readNBytes(SourceRevisionService.MAX_BYTES + 1)
        val result = sources.register(revision, actor, format, bytes)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result.stored.toResponse())
    }

    @GetMapping
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable sourceId: UUID,
        @PathVariable digest: String,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserSourceRevision> {
        val actor = actors.resolve(tenantId, principal).actor.id
        val stored = sources.get(revision(tenantId, sourceId, digest), actor)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stored.toResponse())
    }

    @GetMapping("/compilations/commonmark-v1")
    fun compile(
        @PathVariable tenantId: UUID,
        @PathVariable sourceId: UUID,
        @PathVariable digest: String,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserSourceCompilation> {
        val actor = actors.resolve(tenantId, principal).actor.id
        val result = compilation.compile(revision(tenantId, sourceId, digest), actor)
        val response =
            BrowserSourceCompilation(
                result.extraction.id,
                result.extraction.extractorRevision,
                sourceId,
                digest,
                result.observations.map {
                    BrowserSourceObservation(
                        it.kind.name,
                        it.citation.span.start,
                        it.citation.span.endExclusive,
                        it.citation.quote,
                    )
                },
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response)
    }

    @ExceptionHandler(SourceUnavailable::class, SourceConflict::class, InvalidSource::class, SourceTooLarge::class)
    fun failure(error: RuntimeException): ResponseEntity<Void> =
        ResponseEntity
            .status(
                when (error) {
                    is SourceUnavailable -> HttpStatus.NOT_FOUND
                    is SourceConflict -> HttpStatus.CONFLICT
                    is SourceTooLarge -> HttpStatus.PAYLOAD_TOO_LARGE
                    else -> HttpStatus.UNPROCESSABLE_ENTITY
                },
            ).cacheControl(CacheControl.noStore())
            .build()

    private fun revision(
        tenant: UUID,
        source: UUID,
        digest: String,
    ): SourceRevision =
        try {
            SourceRevision(TenantId(tenant), SourceId(source), SourceDigest(digest))
        } catch (_: IllegalArgumentException) {
            throw InvalidSource()
        }
}

/** Version-one browser source representation; content is untrusted plain text, never rendered HTML. */
data class BrowserSourceRevision(
    val sourceId: UUID,
    val digest: String,
    val mediaType: String,
    val text: String,
    val submittedBy: UUID,
    val recordedAt: Instant,
)

private fun StoredSourceRevision.toResponse(): BrowserSourceRevision =
    BrowserSourceRevision(
        source.revision.sourceId.value,
        source.revision.digest.value,
        if (source.format == EvidenceSourceFormat.COMMONMARK) "text/markdown" else "application/vnd.oai.openapi+json",
        source.text,
        submittedBy.value,
        recordedAt,
    )

/** Version-one compilation projection. Observations retain original syntax and carry no semantic acceptance. */
data class BrowserSourceCompilation(
    val extractionId: UUID,
    val extractorRevision: String,
    val sourceId: UUID,
    val digest: String,
    val observations: List<BrowserSourceObservation>,
)

/** Half-open Unicode code-point positions into the exact source text, never HTML output. */
data class BrowserSourceObservation(
    val kind: String,
    val start: Int,
    val endExclusive: Int,
    val quote: String,
)
