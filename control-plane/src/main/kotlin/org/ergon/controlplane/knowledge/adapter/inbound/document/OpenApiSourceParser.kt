package org.ergon.controlplane.knowledge.adapter.inbound.document

import com.fasterxml.jackson.core.JsonProcessingException
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.controlplane.knowledge.application.SourceCompilation
import org.ergon.controlplane.knowledge.application.SourceObservationParser
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceObservation
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.evidence.domain.SourceSpan
import org.springframework.stereotype.Component
import java.util.UUID

/** OpenAPI 3.0 JSON only. Original pointers remain authoritative; Swagger never resolves or fetches references. */
@Component
class OpenApiSourceParser : SourceObservationParser {
    override fun parse(source: EvidenceSource): SourceCompilation {
        if (source.format != EvidenceSourceFormat.OPENAPI_3_0_JSON ||
            source.text.toByteArray(Charsets.UTF_8).size > SourceRevisionService.MAX_BYTES
        ) {
            throw InvalidSource()
        }
        val json = preflight(source.text)
        val options =
            ParseOptions().apply {
                isResolve = false
                isResolveFully = false
                isFlatten = false
            }
        val parsed = OpenAPIV3Parser().readContents(source.text, null, options)
        if (parsed.openAPI == null || !parsed.messages.isNullOrEmpty()) throw InvalidSource()
        val observations =
            json.ranges.entries.sortedBy { it.value.start }.mapNotNull { (pointer, range) ->
                kind(pointer)?.let { kind ->
                    val text = source.text
                    val citation =
                        SourceCitation(
                            source.revision,
                            SourceSpan(text.codePointCount(0, range.start), text.codePointCount(0, range.end)),
                            text.substring(range.start, range.end),
                        )
                    check(citation.verifyAgainst(source)) { "OpenAPI citation failed verification" }
                    SourceObservation(kind, citation, pointer)
                }
            }
        validateOutput(observations)
        val revision = source.revision
        val key = "$REVISION:${revision.tenantId.value}:${revision.sourceId.value}:${revision.digest.value}"
        return SourceCompilation(
            EvidenceExtraction(UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)), revision, REVISION),
            observations,
        )
    }

    private fun preflight(text: String): OpenApiJsonSource =
        try {
            OpenApiJsonSource(text)
        } catch (_: JsonProcessingException) {
            throw InvalidSource()
        }

    private fun validateOutput(observations: List<SourceObservation>) {
        val quoted = observations.sumOf { it.citation.span.endExclusive - it.citation.span.start }
        if (observations.size > MAX_OBSERVATIONS || quoted > MAX_QUOTED_CODE_POINTS) throw InvalidSource()
    }

    private fun kind(pointer: String): SourceObservationKind? =
        when {
            OPERATION.matches(pointer) -> SourceObservationKind.API_OPERATION
            PARAMETER.matches(pointer) -> SourceObservationKind.API_PARAMETER
            RESPONSE.matches(pointer) -> SourceObservationKind.API_RESPONSE
            pointer.endsWith("/description") -> SourceObservationKind.API_DESCRIPTION
            else -> null
        }

    companion object {
        const val REVISION = "openapi-3.0-swagger-2.1.48-v1"
        const val MAX_OBSERVATIONS = 256
        const val MAX_QUOTED_CODE_POINTS = 131_072
        private const val METHOD = "(?:get|put|post|delete|options|head|patch|trace)"
        private val OPERATION = Regex("/paths/[^/]+/$METHOD")
        private val PARAMETER = Regex("(?:/paths/[^/]+(?:/$METHOD)?/parameters/[0-9]+|/components/parameters/[^/]+)")
        private val RESPONSE = Regex("(?:/paths/[^/]+/$METHOD/responses/[^/]+|/components/responses/[^/]+)")
    }
}
