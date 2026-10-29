package org.ergon.evidence.domain

import org.ergon.identity.domain.TenantId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Stable source identity; meaningful only together with a tenant and exact revision. */
@JvmInline
value class SourceId(
    val value: UUID,
)

/** SHA-256 of original bytes, encoded as exactly 64 lowercase hexadecimal characters. */
@JvmInline
value class SourceDigest(
    val value: String,
) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "source digest must be lowercase SHA-256 hex" }
    }
}

/**
 * Exact immutable source identity. A changed digest is a different revision even
 * when the tenant and logical source are unchanged. A digest is attribution,
 * not proof of authenticity, correctness, or permission to disclose content.
 */
data class SourceRevision(
    val tenantId: TenantId,
    val sourceId: SourceId,
    val digest: SourceDigest,
)

/** Supported authoring formats; neither format permits automatic external reference fetching. */
enum class EvidenceSourceFormat {
    COMMONMARK,
    OPENAPI_3_0_JSON,
}

/**
 * Immutable, strictly decoded UTF-8 source. No newline, BOM, whitespace or Unicode
 * normalization occurs. Input byte arrays are not retained; malformed UTF-8 is
 * rejected rather than replaced. Transport adapters own size limits.
 */
@ConsistentCopyVisibility
data class EvidenceSource private constructor(
    val revision: SourceRevision,
    val format: EvidenceSourceFormat,
    val text: String,
) {
    companion object {
        /**
         * Hashes and decodes a defensive snapshot of [bytes].
         * @throws java.nio.charset.CharacterCodingException for malformed UTF-8.
         */
        fun fromUtf8(
            tenantId: TenantId,
            sourceId: SourceId,
            format: EvidenceSourceFormat,
            bytes: ByteArray,
        ): EvidenceSource {
            val snapshot = bytes.copyOf()
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(snapshot))
                    .toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(snapshot).toHexString()
            return EvidenceSource(SourceRevision(tenantId, sourceId, SourceDigest(digest)), format, text)
        }
    }
}

/**
 * Non-empty half-open span in Unicode code points in the original decoded text.
 * CR and LF each count as one code point; offsets are neither bytes nor JVM char indices.
 */
data class SourceSpan(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0 && endExclusive > start) { "source span must be non-empty and non-negative" }
    }
}

/**
 * Version-one citation to an exact source revision. Construction proves shape;
 * [verifyAgainst] must succeed before a citation can be treated as attributable.
 * A matching quotation establishes location only, never truth or authority.
 */
data class SourceCitation(
    val revision: SourceRevision,
    val span: SourceSpan,
    val quote: String,
) {
    init {
        require(quote.isNotEmpty()) { "citation quote must not be empty" }
    }

    /** Rejects other tenants/revisions, out-of-range spans and altered quotations. */
    fun verifyAgainst(source: EvidenceSource): Boolean {
        val text = source.text
        if (source.revision != revision || span.endExclusive > text.codePointCount(0, text.length)) return false
        val start = text.offsetByCodePoints(0, span.start)
        val end = text.offsetByCodePoints(0, span.endExclusive)
        return text.substring(start, end) == quote
    }
}

/** Extraction identity is tenant-scoped through its exact source, never a global cache key. */
data class EvidenceExtraction(
    val id: UUID,
    val source: SourceRevision,
    val extractorRevision: String,
) {
    init {
        require(extractorRevision.matches(Regex("[a-z0-9][a-z0-9._-]{0,99}"))) {
            "extractor revision must be a bounded lowercase identifier"
        }
    }
}

/** Review labels only; acceptance never supplies operational facts, capability grants or outcome proof. */
enum class EvidenceProposalStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
}
