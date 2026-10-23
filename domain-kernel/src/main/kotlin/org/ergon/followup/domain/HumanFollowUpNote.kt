package org.ergon.followup.domain

import java.util.UUID

/** Client-chosen immutable note identity, scoped to one tenant and work item. */
@JvmInline
value class HumanFollowUpNoteId(
    val value: UUID,
)

/** Rejects text that cannot be stored losslessly as a bounded PostgreSQL note. */
class InvalidHumanFollowUpNoteTextException :
    IllegalArgumentException(
        "note must contain 1–4000 Unicode code points of nonblank text " +
            "without NUL or unpaired surrogates",
    )

/**
 * Untrusted internal commentary, never an observation, fact, instruction or proof.
 * Preserves whitespace and exact Unicode text for replay; accepts at most 4000
 * code points, matching PostgreSQL CHAR_LENGTH rather than UTF-16 code units.
 * @throws InvalidHumanFollowUpNoteTextException when text violates these constraints.
 */
@JvmInline
value class HumanFollowUpNoteText(
    val value: String,
) {
    init {
        val invalidEncoding =
            value.contains('\u0000') ||
                value.codePoints().anyMatch { it in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code }
        if (value.isBlank() || value.codePointCount(0, value.length) > MAX_NOTE_CODE_POINTS || invalidEncoding) {
            throw InvalidHumanFollowUpNoteTextException()
        }
    }
}

private const val MAX_NOTE_CODE_POINTS = 4000
