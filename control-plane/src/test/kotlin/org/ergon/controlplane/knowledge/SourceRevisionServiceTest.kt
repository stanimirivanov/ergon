package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.controlplane.knowledge.application.SourceConflict
import org.ergon.controlplane.knowledge.application.SourceRevisionRepository
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SourceRevisionServiceTest {
    private val repository = Mockito.mock(SourceRevisionRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val actor = HumanActorId(UUID(0, 1))
    private val source =
        EvidenceSource.fromUtf8(
            TenantId(UUID(0, 2)),
            SourceId(UUID(0, 3)),
            EvidenceSourceFormat.COMMONMARK,
            byteArrayOf(),
        )
    private val service = SourceRevisionService(repository, authorities, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `absent authority prevents reads and writes before private storage access`() {
        assertThatThrownBy { service.get(source.revision, actor) }.isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { service.register(source.revision, actor, source.format, byteArrayOf()) }
            .isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun `invalid bytes size and digest fail before storage`() {
        allow()
        assertThatThrownBy { service.register(source.revision, actor, source.format, byteArrayOf(0xff.toByte())) }
            .isInstanceOf(InvalidSource::class.java)
        assertThatThrownBy { service.register(source.revision, actor, source.format, ByteArray(65_537)) }
            .isInstanceOf(SourceTooLarge::class.java)
        assertThatThrownBy { service.register(source.revision, actor, source.format, "changed".toByteArray()) }
            .isInstanceOf(SourceConflict::class.java)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun `missing revision remains unavailable and storage errors propagate`() {
        allow()
        assertThatThrownBy { service.get(source.revision, actor) }.isInstanceOf(SourceUnavailable::class.java)
        Mockito.`when`(repository.find(source.revision)).thenThrow(IllegalStateException("offline"))
        assertThatThrownBy { service.get(source.revision, actor) }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun allow() {
        Mockito
            .`when`(authorities.findCurrent(source.revision.tenantId, actor, ApprovalAuthority.RESOLVER, null, now))
            .thenReturn(Mockito.mock(StoredApprovalAuthorityEvidence::class.java))
    }
}
