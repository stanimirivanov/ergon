package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.identity.domain.HumanActorId
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ApprovalAuthorityEvidenceTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val actorId = HumanActorId(UUID.randomUUID())
    private val source = ApprovalAuthorityEvidenceSource.create("workforce-sso", "claims/assertion-42")

    @Test
    fun `requester authority requires one case while resolver authority is tenant-wide`() {
        evidence(ApprovalAuthority.REQUESTER, CaseId(UUID.randomUUID()))
        evidence(ApprovalAuthority.RESOLVER, null)

        assertThatThrownBy { evidence(ApprovalAuthority.REQUESTER, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { evidence(ApprovalAuthority.RESOLVER, CaseId(UUID.randomUUID())) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `evidence expires at its exclusive end instant`() {
        val evidence = evidence(ApprovalAuthority.RESOLVER, null)

        assertThat(evidence.statusAt(evidence.expiresAt.minusNanos(1)))
            .isEqualTo(ApprovalAuthorityEvidenceStatus.CURRENT)
        assertThat(evidence.statusAt(evidence.expiresAt)).isEqualTo(ApprovalAuthorityEvidenceStatus.EXPIRED)
    }

    @Test
    fun `rejects evidence lasting longer than the domain maximum`() {
        assertThatThrownBy {
            ApprovalAuthorityEvidence(
                id = ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                actorId = actorId,
                authority = ApprovalAuthority.RESOLVER,
                caseId = null,
                source = source,
                attestedAt = now,
                expiresAt = now.plus(ApprovalAuthorityEvidence.MAX_LIFETIME).plusSeconds(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun evidence(
        authority: ApprovalAuthority,
        caseId: CaseId?,
    ) = ApprovalAuthorityEvidence(
        id = ApprovalAuthorityEvidenceId(UUID.randomUUID()),
        actorId = actorId,
        authority = authority,
        caseId = caseId,
        source = source,
        attestedAt = now,
        expiresAt = now.plus(Duration.ofHours(1)),
    )
}
