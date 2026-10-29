package org.ergon.controlplane.knowledge.adapter.inbound.document

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.ergon.controlplane.knowledge.application.InvalidSource

/** Original UTF-16 token ranges; no normalized Swagger value is used as citation text. */
internal data class JsonSourceRange(
    val start: Int,
    val end: Int,
)

/** Strict bounded JSON indexing precedes Swagger, including every reference in extension objects. */
internal class OpenApiJsonSource(
    text: String,
) {
    private val factory =
        JsonFactory
            .builder()
            .streamReadConstraints(
                StreamReadConstraints
                    .builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .maxNumberLength(MAX_NUMBER_LENGTH)
                    .build(),
            ).build()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    val root: JsonNode = ObjectMapper(factory).readTree(text) ?: throw InvalidSource()
    val ranges = linkedMapOf<String, JsonSourceRange>()

    init {
        factory.createParser(text).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) throw InvalidSource()
            index(parser, "")
            if (parser.nextToken() != null) throw InvalidSource()
        }
        if (!root.path("openapi").asText().matches(Regex("3\\.0\\.[0-9]+"))) throw InvalidSource()
        validateReferences()
    }

    private fun index(
        parser: JsonParser,
        pointer: String,
    ) {
        if (ranges.size >= MAX_NODES) throw InvalidSource()
        val start = parser.tokenLocation.charOffset.toInt()
        when (parser.currentToken()) {
            JsonToken.START_OBJECT -> {
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    val key = parser.currentName.replace("~", "~0").replace("/", "~1")
                    parser.nextToken()
                    index(parser, "$pointer/$key")
                }
            }

            JsonToken.START_ARRAY -> {
                var index = 0
                while (parser.nextToken() != JsonToken.END_ARRAY) index(parser, "$pointer/${index++}")
            }

            else -> {
                parser.text
            } // Force lazy string decoding before recording the end offset.
        }
        if (ranges.size >= MAX_NODES) throw InvalidSource()
        ranges[pointer] = JsonSourceRange(start, parser.currentLocation.charOffset.toInt())
    }

    private fun validateReferences() {
        val references =
            ranges.keys.filter { it.endsWith("/\$ref") }.associate { location ->
                location.removeSuffix("/\$ref") to referenceTarget(location)
            }
        if (references.size > MAX_REFERENCES) throw InvalidSource()
        validateReferenceGraph(references)
    }

    private fun referenceTarget(location: String): String {
        val value = root.at(location)
        val reference = value.asText()
        if (!value.isTextual || !LOCAL_POINTER.matches(reference)) throw InvalidSource()
        val target = reference.drop(1)
        if (root.at(target).isMissingNode) throw InvalidSource()
        return target
    }

    private fun validateReferenceGraph(references: Map<String, String>) {
        var visits = 0

        fun follow(
            target: String,
            trail: Set<String>,
        ) {
            if (++visits > MAX_NODES || trail.size >= MAX_REFERENCE_DEPTH || target in trail) throw InvalidSource()
            for ((owner, next) in references) {
                if (owner == target || owner.startsWith("$target/")) follow(next, trail + target)
            }
        }
        references.values.forEach { follow(it, emptySet()) }
    }

    companion object {
        private val LOCAL_POINTER = Regex("^#/(?:[^~%]|~[01])*$")
        const val MAX_DEPTH = 32
        const val MAX_NUMBER_LENGTH = 1000
        const val MAX_NODES = 4096
        const val MAX_REFERENCES = 128
        const val MAX_REFERENCE_DEPTH = 16
    }
}
