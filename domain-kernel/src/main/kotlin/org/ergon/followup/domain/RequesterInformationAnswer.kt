package org.ergon.followup.domain

import java.util.UUID

/** Tenant-wide answer intent identity, not evidence of current access or successful delivery. */
@JvmInline
value class RequesterInformationResponseId(
    val value: UUID,
)

/** Invalid answer content, distinct from transport, authorization or storage failure. */
class InvalidRequesterInformationAnswerException :
    IllegalArgumentException(
        "answer must be nonblank, at most 4000 Unicode code points, " +
            "without NUL or unpaired surrogates",
    )

/**
 * Exact untrusted requester source text; neither a bound fact nor consent, approval or proof.
 * Preserves whitespace for intent comparison. The code-point limit matches PostgreSQL CHAR_LENGTH.
 * @throws InvalidRequesterInformationAnswerException for blank, oversized or invalid Unicode text.
 */
@JvmInline
value class RequesterInformationAnswer(
    val value: String,
) {
    init {
        val invalidEncoding =
            value.contains('\u0000') ||
                value.codePoints().anyMatch { it in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code }
        if (value.isBlank() || value.codePointCount(0, value.length) > MAX_ANSWER_CODE_POINTS || invalidEncoding) {
            throw InvalidRequesterInformationAnswerException()
        }
    }
}

private const val MAX_ANSWER_CODE_POINTS = 4000
