package org.ergon.controlplane.evidence.adapter.out.persistence

import com.fasterxml.jackson.annotation.JsonInclude
import org.ergon.evidence.domain.DocumentedOperation
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.ProcedureContent
import org.ergon.evidence.domain.ProcedureStep
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceDigest
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceRevision
import org.ergon.evidence.domain.SourceSpan
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Versioned storage DTOs deliberately do not serialize domain or browser types directly. */
internal data class ProcedureQuote(
    val start: Int,
    val end: Int,
    val quote: String,
) {
    fun citation(source: SourceRevision): SourceCitation = SourceCitation(source, SourceSpan(start, end), quote)
}

internal data class ProcedureOperationDefinition(
    val sourceId: UUID,
    val digest: String,
    val extractionId: UUID,
    val compilerRevision: String,
    val observationIndex: Int,
    val pointer: String,
    val citation: ProcedureQuote,
)

internal data class ProcedureStepDefinition(
    val citation: ProcedureQuote,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val operation: ProcedureOperationDefinition?,
)

internal data class ProcedureDefinition(
    val citation: ProcedureQuote,
    val steps: List<ProcedureStepDefinition>,
)

/** Exact ordered content; any unsupported/corrupt durable shape is an operational failure, not client validation. */
@Component
class ProcedureDefinitionCodec(
    mapper: JsonMapper,
) {
    private val json = mapper.rebuild().propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE).build()
    private val reader =
        json
            .readerFor(ProcedureDefinition::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun encode(content: ProcedureContent): String =
        json.writeValueAsString(
            ProcedureDefinition(
                content.citation.quoteDefinition(),
                content.steps.map { step ->
                    val operation =
                        step.operation?.let {
                            ProcedureOperationDefinition(
                                it.citation.revision.sourceId.value,
                                it.citation.revision.digest.value,
                                it.extraction.id,
                                it.extraction.extractorRevision,
                                it.observationIndex,
                                it.jsonPointer,
                                it.citation.quoteDefinition(),
                            )
                        }
                    ProcedureStepDefinition(step.citation.quoteDefinition(), operation)
                },
            ),
        )

    fun decode(
        source: SourceRevision,
        title: String,
        definition: String,
    ): ProcedureContent =
        try {
            val stored = reader.readValue<ProcedureDefinition>(definition)
            val steps =
                stored.steps.mapIndexed { index, step ->
                    val operation =
                        step.operation?.let {
                            val api = SourceRevision(source.tenantId, SourceId(it.sourceId), SourceDigest(it.digest))
                            DocumentedOperation(
                                EvidenceExtraction(it.extractionId, api, it.compilerRevision),
                                it.observationIndex,
                                it.citation.citation(api),
                                it.pointer,
                            )
                        }
                    ProcedureStep(index + 1, step.citation.citation(source), operation)
                }
            ProcedureContent.create(title, stored.citation.citation(source), steps)
        } catch (error: JacksonException) {
            throw IllegalStateException("stored procedure definition is invalid", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("stored procedure invariants are invalid", error)
        }
}

private fun SourceCitation.quoteDefinition(): ProcedureQuote = ProcedureQuote(span.start, span.endExclusive, quote)
