package org.ergon.controlplane.followup.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Human follow-up claim paired with the database instant at which it became durable. */
data class StoredHumanFollowUpClaim(
    val claim: HumanFollowUpClaim,
    val recordedAt: Instant,
)

/** Result of first recording or idempotently replaying one resolver claim. */
data class HumanFollowUpClaimRecording(
    val storedClaim: StoredHumanFollowUpClaim,
    val created: Boolean,
)

/** Active follow-up work paired with the immutable claim that gives one resolver ownership. */
data class ResolverOwnedHumanFollowUpWork(
    val workItem: StoredHumanFollowUpWorkItem,
    val claim: StoredHumanFollowUpClaim,
)

/** Stable keyset position in a resolver's oldest-claimed-first work view. */
data class ResolverOwnedHumanFollowUpCursor(
    val claimedAt: Instant,
    val claimId: HumanFollowUpClaimId,
)

/** A bounded page of active work owned by one resolver. */
data class ResolverOwnedHumanFollowUpPage(
    val items: List<ResolverOwnedHumanFollowUpWork>,
    val nextCursor: ResolverOwnedHumanFollowUpCursor?,
)

/** Durable serialization and resolver-scoped lookup boundary for follow-up claims. */
interface HumanFollowUpClaimRepository {
    /**
     * Locks one open work item until the caller's transaction completes.
     *
     * @return `true` when the tenant-scoped item exists and is open, otherwise `false`.
     */
    fun lockOpenWorkItem(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
    ): Boolean

    /** @return the claim for [workItemId], or `null` before the work is claimed. */
    fun findByWorkItem(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
    ): StoredHumanFollowUpClaim?

    /** Stores the first and only claim for [claim]'s work item. */
    fun create(
        tenantId: TenantId,
        claim: HumanFollowUpClaim,
    ): StoredHumanFollowUpClaim

    /**
     * Returns the identified claim only under current tenant-wide resolver authority.
     *
     * Absence, a mismatched work item, and insufficient authority all return `null`
     * so callers cannot use this boundary to discover protected work.
     */
    fun findForResolver(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        claimId: HumanFollowUpClaimId,
        actorId: HumanActorId,
        at: Instant,
    ): StoredHumanFollowUpClaim?

    /**
     * Lists the resolver's claimed `OPEN` work after [after] in ascending claim order.
     *
     * [limit] must be positive. Equal claim instants are ordered by claim identity.
     * Implementations return no rows unless [actorId] has current tenant-wide
     * resolver evidence at [at].
     */
    fun listOwnedForResolver(
        tenantId: TenantId,
        actorId: HumanActorId,
        at: Instant,
        after: ResolverOwnedHumanFollowUpCursor?,
        limit: Int,
    ): List<ResolverOwnedHumanFollowUpWork>
}

/** Supplies unpredictable identities without coupling claim use cases to UUID generation. */
fun interface HumanFollowUpClaimIdentityGenerator {
    /** @return a fresh identity suitable for one durable claim. */
    fun next(): HumanFollowUpClaimId
}

/** Signals that follow-up work already belongs to another resolver. */
class HumanFollowUpAlreadyClaimedException(
    workItemId: UUID,
) : RuntimeException("human follow-up work item $workItemId is already claimed")

/** Hides claim absence, mismatched work, and missing resolver authority behind one result. */
class HumanFollowUpClaimNotFoundException(
    claimId: UUID,
) : RuntimeException("human follow-up claim $claimId was not found")

/** Signals malformed or out-of-range resolver-owned work pagination input. */
class InvalidResolverOwnedHumanFollowUpPageException(
    message: String,
) : RuntimeException(message)

/** Establishes and retrieves immutable resolver ownership of open human follow-up work. */
class HumanFollowUpClaimService(
    private val claims: HumanFollowUpClaimRepository,
    private val authorities: HumanAuthorityRepository,
    private val identities: HumanFollowUpClaimIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Claims one open work item for the authenticated resolver or replays their claim.
     *
     * Competing claims serialize on the work item. The first resolver wins; replay by
     * that resolver returns the original claim without consuming a new identity.
     *
     * @throws CurrentHumanFollowUpResolverAuthorityNotFoundException when [actorId]
     *   lacks current tenant-wide resolver evidence.
     * @throws HumanFollowUpWorkItemNotFoundException when [workItemId] is absent.
     * @throws HumanFollowUpAlreadyClaimedException when another resolver owns the work.
     */
    fun claim(
        tenantId: UUID,
        workItemId: UUID,
        actorId: UUID,
    ): HumanFollowUpClaimRecording =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedWorkItemId = HumanFollowUpWorkItemId(workItemId)
            val scopedActorId = HumanActorId(actorId)
            // Match PostgreSQL precision so the first response and durable replay agree.
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val authority =
                authorities
                    .findCurrent(
                        scopedTenantId,
                        scopedActorId,
                        ApprovalAuthority.RESOLVER,
                        null,
                        now,
                    )?.evidence ?: throw CurrentHumanFollowUpResolverAuthorityNotFoundException()
            if (!claims.lockOpenWorkItem(scopedTenantId, scopedWorkItemId)) {
                throw HumanFollowUpWorkItemNotFoundException(workItemId)
            }
            claims.findByWorkItem(scopedTenantId, scopedWorkItemId)?.let { existing ->
                if (existing.claim.resolverActorId != scopedActorId) {
                    throw HumanFollowUpAlreadyClaimedException(workItemId)
                }
                return@required HumanFollowUpClaimRecording(existing, created = false)
            }
            val claim = HumanFollowUpClaim.claim(identities.next(), scopedWorkItemId, authority, now)
            HumanFollowUpClaimRecording(claims.create(scopedTenantId, claim), created = true)
        }

    /**
     * Retrieves one claim without revealing claim or work existence to unauthorized callers.
     *
     * @throws HumanFollowUpClaimNotFoundException when the coordinates do not identify
     *   a claim visible under the caller's current resolver authority.
     */
    fun get(
        tenantId: UUID,
        workItemId: UUID,
        claimId: UUID,
        actorId: UUID,
    ): StoredHumanFollowUpClaim =
        claims.findForResolver(
            TenantId(tenantId),
            HumanFollowUpWorkItemId(workItemId),
            HumanFollowUpClaimId(claimId),
            HumanActorId(actorId),
            clock.instant(),
        ) ?: throw HumanFollowUpClaimNotFoundException(claimId)

    /**
     * Lists active work owned by the authenticated resolver, oldest claim first.
     *
     * [afterClaimedAt] and [afterClaimId] must either both be absent for the first
     * page or both identify the final claim returned by an earlier page. A caller
     * without current resolver authority receives an empty page.
     *
     * @throws InvalidResolverOwnedHumanFollowUpPageException when [limit] is
     *   outside `1..100` or only one cursor component is supplied.
     */
    fun listOwned(
        tenantId: UUID,
        actorId: UUID,
        limit: Int,
        afterClaimedAt: Instant?,
        afterClaimId: UUID?,
    ): ResolverOwnedHumanFollowUpPage {
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidResolverOwnedHumanFollowUpPageException(
                "limit must be between 1 and $MAX_PAGE_SIZE",
            )
        }
        if ((afterClaimedAt == null) != (afterClaimId == null)) {
            throw InvalidResolverOwnedHumanFollowUpPageException(
                "afterClaimedAt and afterClaimId must be supplied together",
            )
        }
        val cursor =
            afterClaimedAt?.let {
                ResolverOwnedHumanFollowUpCursor(it, HumanFollowUpClaimId(requireNotNull(afterClaimId)))
            }
        val results =
            claims.listOwnedForResolver(
                TenantId(tenantId),
                HumanActorId(actorId),
                clock.instant(),
                cursor,
                limit + 1,
            )
        val items = results.take(limit)
        val nextCursor =
            if (results.size > limit) {
                val finalClaim = items.last().claim.claim
                ResolverOwnedHumanFollowUpCursor(finalClaim.claimedAt, finalClaim.id)
            } else {
                null
            }
        return ResolverOwnedHumanFollowUpPage(items, nextCursor)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}

/** Signals that the authenticated actor cannot currently claim follow-up work. */
class CurrentHumanFollowUpResolverAuthorityNotFoundException :
    RuntimeException(
        "current tenant-wide resolver authority is required",
    )
