package org.ergon.evidence.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.nio.charset.CharacterCodingException
import java.util.UUID

class SourceEvidenceTest {
    private val tenant = TenantId(UUID(0, 1))
    private val id = SourceId(UUID(0, 2))
    private val format = EvidenceSourceFormat.COMMONMARK

    @Test
    fun `digest covers original bytes without newline normalization`() {
        val bytes = "abc".toByteArray()
        val source = source(bytes)
        bytes[0] = 0
        assertThat(source.text).isEqualTo("abc")
        assertThat(source.revision.digest.value)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(source("a\r\nb".toByteArray()).revision).isNotEqualTo(source("a\nb".toByteArray()).revision)
        assertThat(source("abc".toByteArray())).isEqualTo(source)
    }

    @Test
    fun `citation uses code points and preserves CRLF and combining characters`() {
        val source = source("A😀\r\ne\u0301Z".toByteArray())
        assertThat(SourceCitation(source.revision, SourceSpan(1, 6), "😀\r\ne\u0301").verifyAgainst(source)).isTrue()
        assertThat(SourceCitation(source.revision, SourceSpan(1, 6), "😀\né").verifyAgainst(source)).isFalse()
        assertThat(SourceCitation(source.revision, SourceSpan(6, 7), "Z").verifyAgainst(source)).isTrue()
        assertThat(SourceCitation(source.revision, SourceSpan(0, 8), "outside").verifyAgainst(source)).isFalse()
    }

    @Test
    fun `matching text cannot substitute another tenant source or digest`() {
        val source = source("abc".toByteArray())
        val citation = SourceCitation(source.revision, SourceSpan(0, 3), "abc")
        val otherTenant = EvidenceSource.fromUtf8(TenantId(UUID(0, 3)), id, source.format, "abc".toByteArray())
        val otherSource = EvidenceSource.fromUtf8(tenant, SourceId(UUID(0, 4)), source.format, "abc".toByteArray())
        assertThat(citation.verifyAgainst(otherTenant)).isFalse()
        assertThat(citation.verifyAgainst(otherSource)).isFalse()
        assertThat(citation.verifyAgainst(source("abcd".toByteArray()))).isFalse()
    }

    @Test
    fun `invalid UTF8 is rejected rather than replaced`() {
        assertThatThrownBy { source(byteArrayOf(0xc3.toByte(), 0x28)) }
            .isInstanceOf(CharacterCodingException::class.java)
    }

    @Test
    fun `rejects malformed digests empty quotes and invalid spans`() {
        for (digest in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            assertThatThrownBy { SourceDigest(digest) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        for ((start, end) in listOf(-1 to 1, 0 to 0, 2 to 1)) {
            assertThatThrownBy { SourceSpan(start, end) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { SourceCitation(source(byteArrayOf()).revision, SourceSpan(0, 1), "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `extractor revision must be explicit and bounded`() {
        val revision = source(byteArrayOf()).revision
        for (version in listOf("", " ", "Latest", "a".repeat(101))) {
            assertThatThrownBy { EvidenceExtraction(UUID(0, 5), revision, version) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(EvidenceExtraction(UUID(0, 5), revision, "markdown-v1").source).isEqualTo(revision)
    }

    private fun source(bytes: ByteArray): EvidenceSource = EvidenceSource.fromUtf8(tenant, id, format, bytes)
}
