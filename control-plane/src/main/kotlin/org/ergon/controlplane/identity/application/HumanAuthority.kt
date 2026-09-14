package org.ergon.controlplane.identity.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.HumanActor
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Immutable human actor identity paired with its database recording time. */
data class StoredHumanActor(
    val actor: HumanActor,
    val registeredAt: Instant,
    val recordedAt: Instant,
)

/** Immutable authority evidence paired with its database recording time. */
data class StoredApprovalAuthorityEvidence(
    val evidence: ApprovalAuthorityEvidence,
    val recordedAt: Instant,
)

/** Authority evidence plus its status at one application-clock instant. */
data class ApprovalAuthorityEvidenceView(
    val stored: StoredApprovalAuthorityEvidence,
    val status: ApprovalAuthorityEvidenceStatus,
)

/** Tenant-scoped registry of immutable human identities and their authority attestations. */
interface HumanAuthorityRepository {
    /**
     * Persists [actor] as registered at [registeredAt].
     *
     * @throws HumanActorIdentityAlreadyExistsException when the same tenant, provider,
     *   and subject are already bound to an actor.
     */
    fun create(
        tenantId: TenantId,
        actor: HumanActor,
        registeredAt: Instant,
    ): StoredHumanActor

    /** @return the tenant-scoped actor, or `null` when it is absent. */
    fun find(
        tenantId: TenantId,
        actorId: HumanActorId,
    ): StoredHumanActor?

    /**
     * Finds the actor bound to a verified external identity within [tenantId].
     *
     * [identityProvider] and [subject] must originate from a trusted authentication
     * adapter; this lookup does not verify credentials or token claims.
     *
     * @return the tenant-scoped actor, or `null` when the identity is not registered.
     */
    fun find(
        tenantId: TenantId,
        identityProvider: String,
        subject: String,
    ): StoredHumanActor?

    /** Stores [evidence] without granting approval or changing a resolution run. */
    fun create(
        tenantId: TenantId,
        evidence: ApprovalAuthorityEvidence,
    ): StoredApprovalAuthorityEvidence

    /** @return the tenant-scoped evidence, or `null` when it is absent. */
    fun find(
        tenantId: TenantId,
        evidenceId: ApprovalAuthorityEvidenceId,
    ): StoredApprovalAuthorityEvidence?

    /**
     * Selects current evidence that gives [actorId] the requested authority and scope.
     *
     * [caseId] is required for requester authority and absent for resolver authority.
     * Implementations must use [at] as the exclusive expiry boundary and select
     * deterministically when more than one attestation is current.
     *
     * @return matching evidence, or `null` when no current attestation exists.
     */
    fun findCurrent(
        tenantId: TenantId,
        actorId: HumanActorId,
        authority: ApprovalAuthority,
        caseId: CaseId?,
        at: Instant,
    ): StoredApprovalAuthorityEvidence?
}

/** Values required to record one externally sourced approval-authority attestation. */
data class AttestApprovalAuthorityCommand(
    val tenantId: UUID,
    val actorId: UUID,
    val authority: ApprovalAuthority,
    val caseId: UUID?,
    val sourceProvider: String,
    val sourceReference: String,
    val expiresAt: Instant,
)

/** Supplies unpredictable actor and evidence identities without exposing UUID generation to use cases. */
fun interface HumanAuthorityIdentityGenerator {
    /** @return a fresh identity suitable for durable actor or authority-evidence history. */
    fun next(): UUID
}

/** Signals tenant-scoped actor absence without revealing another tenant's identity registry. */
class HumanActorNotFoundException(
    actorId: UUID,
) : RuntimeException("human actor $actorId was not found")

/** Signals that an authenticated external identity has no actor in the requested tenant. */
class AuthenticatedHumanActorNotRegisteredException :
    RuntimeException(
        "authenticated identity is not registered in this tenant",
    )

/** Identifies the actor already bound to one tenant/provider/subject tuple. */
class HumanActorIdentityAlreadyExistsException(
    val actorId: UUID,
) : RuntimeException("external identity is already bound to human actor $actorId")

/** Signals tenant-scoped authority-evidence absence. */
class ApprovalAuthorityEvidenceNotFoundException(
    evidenceId: UUID,
) : RuntimeException("approval authority evidence $evidenceId was not found")

/** Signals that requester authority names a case outside the current tenant boundary. */
class AuthorityEvidenceCaseNotFoundException(
    caseId: UUID,
) : RuntimeException("case $caseId was not found")

/**
 * Registers human identities and attributable, time-bounded approval-authority evidence.
 *
 * These operations establish facts that a later approval decision can evaluate. They do
 * not authenticate the current caller and never create an approval grant.
 */
class HumanAuthorityService(
    private val repository: HumanAuthorityRepository,
    private val cases: CaseTimelineRepository,
    private val identities: HumanAuthorityIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Registers one external identity as an immutable tenant-scoped actor.
     *
     * @throws HumanActorIdentityAlreadyExistsException when the external identity is
     *   already registered in this tenant.
     * @throws IllegalArgumentException when provider or subject constraints are violated.
     */
    fun registerActor(
        tenantId: UUID,
        identityProvider: String,
        subject: String,
    ): StoredHumanActor {
        val actor =
            HumanActor.create(
                id = HumanActorId(identities.next()),
                identityProvider = identityProvider,
                subject = subject,
            )
        val registeredAt = clock.instant()
        return transactionRunner.required {
            repository.create(TenantId(tenantId), actor, registeredAt)
        }
    }

    /**
     * Retrieves a registered actor within one tenant boundary.
     *
     * @throws HumanActorNotFoundException when [actorId] is absent from [tenantId].
     */
    fun getActor(
        tenantId: UUID,
        actorId: UUID,
    ): StoredHumanActor =
        repository.find(TenantId(tenantId), HumanActorId(actorId))
            ?: throw HumanActorNotFoundException(actorId)

    /**
     * Records the external approval-authority attestation described by [command].
     *
     * Requester evidence must name an existing tenant-scoped case; resolver evidence must
     * omit it. The expiry must be after the application clock and at most 24 hours later.
     *
     * @throws HumanActorNotFoundException when the actor is absent from the tenant.
     * @throws AuthorityEvidenceCaseNotFoundException when requester scope names an absent case.
     * @throws IllegalArgumentException when source, scope, or validity constraints are violated.
     */
    fun attest(command: AttestApprovalAuthorityCommand): ApprovalAuthorityEvidenceView =
        transactionRunner.required {
            val scopedTenantId = TenantId(command.tenantId)
            val scopedActorId = HumanActorId(command.actorId)
            repository.find(scopedTenantId, scopedActorId)
                ?: throw HumanActorNotFoundException(command.actorId)
            if (command.authority == ApprovalAuthority.REQUESTER) {
                val requiredCaseId =
                    command.caseId ?: throw IllegalArgumentException("requester authority requires caseId")
                cases.find(scopedTenantId, CaseId(requiredCaseId))
                    ?: throw AuthorityEvidenceCaseNotFoundException(requiredCaseId)
            }
            val now = clock.instant()
            val attestation =
                ApprovalAuthorityEvidence(
                    id = ApprovalAuthorityEvidenceId(identities.next()),
                    actorId = scopedActorId,
                    authority = command.authority,
                    caseId = command.caseId?.let(::CaseId),
                    source =
                        ApprovalAuthorityEvidenceSource.create(
                            command.sourceProvider,
                            command.sourceReference,
                        ),
                    attestedAt = now,
                    expiresAt = command.expiresAt,
                )
            repository.create(scopedTenantId, attestation).toView(now)
        }

    /**
     * Returns one attestation with status evaluated at the current application time.
     *
     * @throws ApprovalAuthorityEvidenceNotFoundException when [evidenceId] is absent from [tenantId].
     */
    fun getEvidence(
        tenantId: UUID,
        evidenceId: UUID,
    ): ApprovalAuthorityEvidenceView {
        val stored =
            repository.find(TenantId(tenantId), ApprovalAuthorityEvidenceId(evidenceId))
                ?: throw ApprovalAuthorityEvidenceNotFoundException(evidenceId)
        return stored.toView(clock.instant())
    }
}

/** Resolves verified external identities to tenant-scoped Ergon actors. */
class HumanActorAuthenticationService(
    private val repository: HumanAuthorityRepository,
) {
    /**
     * Resolves a caller after an inbound adapter has verified its credential and issuer.
     *
     * The provider and subject must be copied from verified claims rather than supplied
     * by the caller. Absence deliberately does not reveal whether the identity is known
     * in another tenant.
     *
     * @throws AuthenticatedHumanActorNotRegisteredException when the identity is not
     *   registered in [tenantId].
     */
    fun resolve(
        tenantId: UUID,
        identityProvider: String,
        subject: String,
    ): StoredHumanActor =
        repository.find(TenantId(tenantId), identityProvider, subject)
            ?: throw AuthenticatedHumanActorNotRegisteredException()
}

private fun StoredApprovalAuthorityEvidence.toView(now: Instant) =
    ApprovalAuthorityEvidenceView(
        stored = this,
        status = evidence.statusAt(now),
    )
