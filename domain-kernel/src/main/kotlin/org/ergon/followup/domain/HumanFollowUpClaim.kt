package org.ergon.followup.domain

import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable resolver claim on human follow-up work. */
@JvmInline
value class HumanFollowUpClaimId(
    val value: UUID,
)

/** Complete values needed to rehydrate a durable human follow-up claim. */
data class HumanFollowUpClaimSnapshot(
    val id: HumanFollowUpClaimId,
    val workItemId: HumanFollowUpWorkItemId,
    val resolverActorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
    val claimedAt: Instant,
)

/**
 * Immutable ownership established when an authorized resolver claims one work item.
 *
 * A claim records the exact resolver and tenant-wide authority evidence accepted at
 * [claimedAt]. It does not close the work, change its priority, or permit reassignment.
 */
@ConsistentCopyVisibility
data class HumanFollowUpClaim private constructor(
    val id: HumanFollowUpClaimId,
    val workItemId: HumanFollowUpWorkItemId,
    val resolverActorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
    val claimedAt: Instant,
) {
    companion object {
        /**
         * Claims [workItemId] using current tenant-wide resolver [authority].
         *
         * @throws IllegalArgumentException when the evidence is not tenant-wide
         *   resolver authority for [claimedAt].
         */
        fun claim(
            id: HumanFollowUpClaimId,
            workItemId: HumanFollowUpWorkItemId,
            authority: ApprovalAuthorityEvidence,
            claimedAt: Instant,
        ): HumanFollowUpClaim {
            require(authority.authority == ApprovalAuthority.RESOLVER && authority.caseId == null) {
                "human follow-up claims require tenant-wide resolver authority"
            }
            require(!claimedAt.isBefore(authority.attestedAt) && claimedAt.isBefore(authority.expiresAt)) {
                "human follow-up claims require current resolver authority"
            }
            return HumanFollowUpClaim(
                id,
                workItemId,
                authority.actorId,
                authority.id,
                claimedAt,
            )
        }

        /** Reconstructs a claim whose actor and authority links were validated by storage. */
        fun rehydrate(snapshot: HumanFollowUpClaimSnapshot): HumanFollowUpClaim =
            HumanFollowUpClaim(
                snapshot.id,
                snapshot.workItemId,
                snapshot.resolverActorId,
                snapshot.authorityEvidenceId,
                snapshot.claimedAt,
            )
    }
}
