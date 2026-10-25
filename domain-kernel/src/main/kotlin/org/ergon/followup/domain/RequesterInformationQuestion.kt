package org.ergon.followup.domain

import java.util.UUID

/** Tenant-scoped immutable intent identity, not a bearer capability or a delivery receipt. */
@JvmInline
value class RequesterInformationRequestId(
    val value: UUID,
)

/** Invalid question text, rather than a storage/transport failure. */
class InvalidRequesterInformationQuestionException :
    IllegalArgumentException(
        "question must be nonblank, at most 4000 Unicode code points, " +
            "without NUL or unpaired surrogates",
    )

/**
 * Exact untrusted requester-facing question, not evidence, consent or an executable instruction.
 * Whitespace is preserved for replay; the limit matches PostgreSQL CHAR_LENGTH.
 * @throws InvalidRequesterInformationQuestionException for blank, oversized or invalid Unicode text.
 */
@JvmInline
value class RequesterInformationQuestion(
    val value: String,
) {
    init {
        val invalidEncoding =
            value.contains('\u0000') ||
                value.codePoints().anyMatch { it in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code }
        if (value.isBlank() || value.codePointCount(0, value.length) > MAX_QUESTION_CODE_POINTS || invalidEncoding) {
            throw InvalidRequesterInformationQuestionException()
        }
    }
}

private const val MAX_QUESTION_CODE_POINTS = 4000
