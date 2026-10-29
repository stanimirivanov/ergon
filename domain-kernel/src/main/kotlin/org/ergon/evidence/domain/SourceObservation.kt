package org.ergon.evidence.domain

/** Syntactic source roles only; an ordered list is not yet a semantic procedure. */
enum class SourceObservationKind {
    HEADING,
    PARAGRAPH,
    ORDERED_LIST,
    API_OPERATION,
    API_PARAMETER,
    API_RESPONSE,
    API_DESCRIPTION,
}

/**
 * One syntactic block and its exact original quotation. Parent list observations
 * may overlap their contained paragraph observations. Order is document preorder.
 * No normalized text, inferred property, confidence or authority is supplied.
 */
data class SourceObservation(
    val kind: SourceObservationKind,
    val citation: SourceCitation,
    /** RFC 6901 pointer for JSON observations; null for non-JSON sources. */
    val jsonPointer: String? = null,
)
