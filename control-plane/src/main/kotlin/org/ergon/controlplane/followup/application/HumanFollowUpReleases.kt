package org.ergon.controlplane.followup.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exact claim and ownership state a resolver intends to release. */
data class HumanFollowUpReleaseCommand(
    val tenantId: UUID,
    val workItemId: UUID,
    val claimId: UUID,
    val actorId: UUID,
    val expectedOwnershipRevision: Long,
)

/** Immutable release audit fact; the work item remains open and unowned. */
data class StoredHumanFollowUpRelease(
    val workItemId: HumanFollowUpWorkItemId,
    val claimId: HumanFollowUpClaimId,
    val resolverActorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
    val ownershipRevision: HumanFollowUpOwnershipRevision,
    val releasedAt: Instant,
    val recordedAt: Instant,
)

/** Distinguishes an applied release from its exact authorized replay. */
data class HumanFollowUpReleaseRecording(
    val release: StoredHumanFollowUpRelease,
    val created: Boolean,
)

/** Reads immutable release receipts and atomically clears current ownership. */
interface HumanFollowUpReleaseRepository {
    /** Returns the release of [claimId], if it was already recorded for this work item. */
    fun findByClaim(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        claimId: HumanFollowUpClaimId,
    ): StoredHumanFollowUpRelease?

    /** Appends the release and advances its projection in the caller's transaction. */
    fun record(
        tenantId: TenantId,
        claim: StoredHumanFollowUpClaim,
        authorityEvidenceId: ApprovalAuthorityEvidenceId,
        revision: HumanFollowUpOwnershipRevision,
        releasedAt: Instant,
    ): StoredHumanFollowUpRelease
}

/** Rejects malformed release intent before consulting authority or storage. */
class InvalidHumanFollowUpReleaseCommandException : RuntimeException("expectedOwnershipRevision must not be negative")

/** Hides absent work, inactive claims, and ownership by another resolver. */
class HumanFollowUpReleaseNotFoundException : RuntimeException("current human follow-up claim was not found")

/** Release remains closed until every ownership reader can interpret later cycles. */
class HumanFollowUpReleaseUnavailableException : RuntimeException("human follow-up release is not enabled")

/** Releases only the exact active claim, preserving every historical owner. */
@Suppress("LongParameterList") // Distinct claim, receipt, authority, and transaction boundaries remain explicit.
class HumanFollowUpReleaseService(
    private val claims: HumanFollowUpClaimRepository,
    private val commands: HumanFollowUpClaimCommandRepository,
    private val releases: HumanFollowUpReleaseRepository,
    private val authorities: HumanAuthorityRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Releases a current claim or replays its already durable release.
     *
     * Current tenant-wide resolver authority is required even on replay. The
     * work-item lock serializes release against both forms of claiming.
     *
     * @throws InvalidHumanFollowUpReleaseCommandException for a negative revision.
     * @throws HumanFollowUpReleaseUnavailableException while rollout has not enabled release.
     * @throws CurrentHumanFollowUpResolverAuthorityNotFoundException without current authority.
     * @throws HumanFollowUpReleaseNotFoundException for absent, inactive, or differently owned claims.
     * @throws HumanFollowUpOwnershipRevisionConflictException when the expected revision is stale.
     */
    @Suppress("ThrowsCount") // Precise authorization, absence, and stale-intent failures avoid leaking ownership state.
    fun release(command: HumanFollowUpReleaseCommand): HumanFollowUpReleaseRecording {
        if (!enabled) {
            throw HumanFollowUpReleaseUnavailableException()
        }
        if (command.expectedOwnershipRevision < 0) {
            throw InvalidHumanFollowUpReleaseCommandException()
        }
        return transactions.required {
            val tenantId = TenantId(command.tenantId)
            val workItemId = HumanFollowUpWorkItemId(command.workItemId)
            val claimId = HumanFollowUpClaimId(command.claimId)
            val actorId = HumanActorId(command.actorId)
            val expectedRevision = HumanFollowUpOwnershipRevision(command.expectedOwnershipRevision)
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val authority =
                authorities.findCurrent(tenantId, actorId, ApprovalAuthority.RESOLVER, null, now)?.evidence
                    ?: throw CurrentHumanFollowUpResolverAuthorityNotFoundException()
            if (!claims.lockOpenWorkItem(tenantId, workItemId)) {
                throw HumanFollowUpReleaseNotFoundException()
            }
            releases.findByClaim(tenantId, workItemId, claimId)?.let { prior ->
                if (prior.resolverActorId != actorId) {
                    throw HumanFollowUpReleaseNotFoundException()
                }
                if (prior.ownershipRevision.value - 1 != expectedRevision.value) {
                    throw HumanFollowUpOwnershipRevisionConflictException()
                }
                return@required HumanFollowUpReleaseRecording(prior, created = false)
            }
            val active = claims.findByWorkItem(tenantId, workItemId)
            if (active == null || active.claim.id != claimId || active.claim.resolverActorId != actorId) {
                throw HumanFollowUpReleaseNotFoundException()
            }
            val currentRevision = commands.currentOwnershipRevision(tenantId, workItemId)
            if (currentRevision != expectedRevision || currentRevision.value == Long.MAX_VALUE) {
                throw HumanFollowUpOwnershipRevisionConflictException()
            }
            val released =
                releases.record(
                    tenantId,
                    active,
                    authority.id,
                    HumanFollowUpOwnershipRevision(currentRevision.value + 1),
                    now,
                )
            HumanFollowUpReleaseRecording(released, created = true)
        }
    }
}
