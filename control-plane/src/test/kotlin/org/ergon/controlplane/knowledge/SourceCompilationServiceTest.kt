package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceObservationParser
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class SourceCompilationServiceTest {
    @Test
    fun `authorization and exact revision retrieval precede parsing`() {
        val sources = Mockito.mock(SourceRevisionService::class.java)
        val parser = Mockito.mock(SourceObservationParser::class.java)
        val source =
            EvidenceSource.fromUtf8(
                TenantId(UUID(0, 1)),
                SourceId(UUID(0, 2)),
                EvidenceSourceFormat.COMMONMARK,
                byteArrayOf(),
            )
        val actor = HumanActorId(UUID(0, 3))
        val service = SourceCompilationService(sources, parser)
        Mockito.`when`(sources.get(source.revision, actor)).thenThrow(SourceUnavailable())
        assertThatThrownBy { service.compile(source.revision, actor) }.isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(parser)
        Mockito.doReturn(StoredSourceRevision(source, actor, Instant.EPOCH)).`when`(sources).get(source.revision, actor)
        Mockito.`when`(parser.parse(source)).thenThrow(IllegalStateException("parser fault"))
        assertThatThrownBy { service.compile(source.revision, actor) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(source.text).isEmpty()
    }
}
