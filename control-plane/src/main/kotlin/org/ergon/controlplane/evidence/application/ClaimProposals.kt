package org.ergon.controlplane.evidence.application

import org.ergon.evidence.domain.ClaimProposal
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

/** Original proposer/time survive exact retries; acceptance belongs to a later review capability. */
data class StoredClaimProposal(
    val proposal: ClaimProposal,
    val proposedBy: HumanActorId,
    val recordedAt: Instant,
)

data class ClaimRegistration(
    val stored: StoredClaimProposal,
    val created: Boolean,
)

/** Implementations atomically recover exact retries and reject changed commands with [ClaimConflict]. */
interface ClaimProposalRepository {
    fun register(
        proposal: ClaimProposal,
        actor: HumanActorId,
    ): ClaimRegistration

    fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredClaimProposal?
}

class ClaimConflict : RuntimeException()
