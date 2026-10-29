package org.ergon.evidence.domain

/** Syntactic source roles only; an ordered list is not yet a semantic procedure. */
enum class SourceObservationKind {
    HEADING,
    PARAGRAPH,
    ORDERED_LIST,
}

/**
 * One syntactic block and its exact original quotation. Parent list observations
 * may overlap their contained paragraph observations. Order is document preorder.
 * No normalized text, inferred property, confidence or authority is supplied.
 */
data class SourceObservation(
    val kind: SourceObservationKind,
    val citation: SourceCitation,
)
