package org.ergon.evidence.domain

import java.util.UUID

/** An attributed operation declaration, never a capability registration or permission to invoke it. */
data class DocumentedOperation(
    val extraction: EvidenceExtraction,
    val observationIndex: Int,
    val citation: SourceCitation,
    val jsonPointer: String,
) {
    init {
        require(observationIndex >= 0 && extraction.source == citation.revision)
        require(jsonPointer.startsWith("/paths/") && jsonPointer.length <= MAX_POINTER_LENGTH)
        require(Charsets.UTF_8.newEncoder().canEncode(jsonPointer) && '\u0000' !in jsonPointer)
    }

    companion object {
        const val MAX_POINTER_LENGTH = 1024
    }
}

/** Position is one-based source order; the original list marker remains in the quotation. */
data class ProcedureStep(
    val position: Int,
    val citation: SourceCitation,
    val operation: DocumentedOperation? = null,
)

/** Exact flat-list selection and immutable ordered steps. No inferred prerequisites or executable semantics. */
@ConsistentCopyVisibility
data class ProcedureContent private constructor(
    val title: String,
    val citation: SourceCitation,
    val steps: List<ProcedureStep>,
) {
    companion object {
        const val MAX_STEPS = 20
        const val MAX_TITLE_LENGTH = 256
        const val MAX_QUOTED_BYTES = 262_144

        /** Rejects unsupported order, overlapping/foreign citations and cross-tenant operation references. */
        fun create(
            title: String,
            citation: SourceCitation,
            steps: List<ProcedureStep>,
        ): ProcedureContent {
            validateTitle(title)
            validateSteps(citation, steps)
            validateQuotes(citation, steps)
            return ProcedureContent(title, citation, java.util.List.copyOf(steps))
        }

        private fun validateTitle(title: String) {
            require(title.isNotBlank() && title == title.trim() && title.length <= MAX_TITLE_LENGTH)
            require(title.none { it.isISOControl() } && Charsets.UTF_8.newEncoder().canEncode(title))
        }

        private fun validateSteps(
            citation: SourceCitation,
            steps: List<ProcedureStep>,
        ) {
            require(steps.size in 1..MAX_STEPS)
            var previousEnd = citation.span.start
            for ((index, step) in steps.withIndex()) {
                require(step.position == index + 1 && step.citation.revision == citation.revision)
                val span = step.citation.span
                val withinList = span.start >= previousEnd && span.endExclusive <= citation.span.endExclusive
                require(withinList)
                val operation = step.operation
                require(operation == null || operation.citation.revision.tenantId == citation.revision.tenantId)
                previousEnd = step.citation.span.endExclusive
            }
        }

        private fun validateQuotes(
            citation: SourceCitation,
            steps: List<ProcedureStep>,
        ) {
            val citations = listOf(citation) + steps.flatMap { listOfNotNull(it.citation, it.operation?.citation) }
            require(citations.none { '\u0000' in it.quote }) { "procedure quotations cannot contain NUL" }
            val size =
                citations.sumOf {
                    it.quote
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong()
                }
            require(size <= MAX_QUOTED_BYTES)
        }
    }
}

/** Immutable documentary proposal, not a reviewed or executable resolution plan. */
data class ProcedureProposal(
    val id: UUID,
    val extraction: EvidenceExtraction,
    val observationIndex: Int,
    val content: ProcedureContent,
) {
    init {
        require(observationIndex >= 0 && extraction.source == content.citation.revision)
    }

    companion object {
        const val BINDING_RULE = "ordered-list-procedure-v1"
    }
}
