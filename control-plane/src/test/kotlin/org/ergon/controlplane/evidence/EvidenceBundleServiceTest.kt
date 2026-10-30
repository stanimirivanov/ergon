package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.EvidenceBundleConflict
import org.ergon.controlplane.evidence.application.EvidenceBundlePreparer
import org.ergon.controlplane.evidence.application.EvidenceBundleRegistration
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.controlplane.evidence.application.EvidenceBundleService
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class EvidenceBundleServiceTest {
    private val b = EvidenceBundleFixtures()
    private val f = b.f
    private val tenant = f.source.tenant
    private val actor = f.source.actor
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val preparer = Mockito.mock(EvidenceBundlePreparer::class.java)
    private val repository = Mockito.mock(EvidenceBundleRepository::class.java)
    private val service = EvidenceBundleService(authorities, preparer, repository, Clock.fixed(f.now, ZoneOffset.UTC))

    @Test
    fun `publication history and exact recovery authorize before private access`() {
        assertThatThrownBy { service.publish(tenant, actor, b.command) }.isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { service.get(tenant, actor, b.command.id) }.isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(preparer, repository)
    }

    @Test
    fun `exact historical receipt recovers without current qualification and retains original cutoff and proof`() {
        val renewed = ApprovalAuthorityEvidenceId(UUID(0, 90))
        allow(renewed)
        Mockito.`when`(repository.find(tenant, b.command.id)).thenReturn(b.stored)
        Mockito
            .`when`(repository.publish(EvidenceReviewAdmission(tenant, actor, renewed), b.command, b.content))
            .thenReturn(EvidenceBundleRegistration(b.stored, false))
        assertThat(service.publish(tenant, actor, b.command).stored).isEqualTo(b.stored)
        assertThat(service.get(tenant, actor, b.command.id)).isEqualTo(b.stored)
        assertThatThrownBy { service.publish(tenant, actor, b.command.copy(procedures = emptyList())) }
            .isInstanceOf(EvidenceBundleConflict::class.java)
        Mockito.verifyNoInteractions(preparer)
    }

    @Test
    fun `new publication uses verified exact preparation and absent history is unavailable`() {
        allow(f.authority)
        Mockito.`when`(preparer.prepare(tenant, actor, b.command)).thenReturn(b.content)
        Mockito
            .`when`(repository.publish(EvidenceReviewAdmission(tenant, actor, f.authority), b.command, b.content))
            .thenReturn(EvidenceBundleRegistration(b.stored, true))
        assertThat(service.publish(tenant, actor, b.command).created).isTrue()
        assertThatThrownBy { service.get(tenant, actor, UUID.randomUUID()) }.isInstanceOf(SourceUnavailable::class.java)
    }

    private fun allow(id: ApprovalAuthorityEvidenceId) {
        val proof =
            ApprovalAuthorityEvidence(
                id,
                actor,
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("test", "resolvers"),
                f.now.minusSeconds(60),
                f.now.plusSeconds(60),
            )
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, f.now))
            .thenReturn(StoredApprovalAuthorityEvidence(proof, f.now))
    }
}
