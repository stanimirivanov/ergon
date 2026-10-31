package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.EvaluationViewerAuthority
import java.time.Instant
import java.util.UUID

/** Only trusted internal issuers may create these attestations; sessions cannot grant themselves access. */
data class ViewerAttestation(
    val actorId: UUID,
    val sourceProvider: String,
    val sourceReference: String,
    val expiresAt: Instant,
)

data class ViewerRegistration(
    val authority: EvaluationViewerAuthority,
    val created: Boolean,
)

enum class SimulationAuthorityKind { EVALUATION_VIEWER, CONTRACT_PROMOTER }

data class AuthorityRevocationIntent(
    val sourceProvider: String,
    val sourceReference: String,
    val reason: String,
) {
    init {
        require(sourceProvider.matches(Regex("[a-z0-9][a-z0-9._-]{0,99}")))
        listOf(sourceReference, reason).forEach {
            require(it.isNotBlank() && it == it.trim() && it.length <= MAX_PROVENANCE)
            require(it.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(it))
        }
    }
}

data class AuthorityRevocation(
    val intent: AuthorityRevocationIntent,
    val revokedAt: Instant,
    val created: Boolean,
)

interface SimulationLabAuthority {
    fun attest(
        tenant: UUID,
        id: UUID,
        intent: ViewerAttestation,
    ): ViewerRegistration

    /** Database-time snapshot; false includes revoked, expired, absent and tenant-foreign evidence. */
    fun canView(
        tenant: UUID,
        actor: UUID,
    ): Boolean

    /** Revocation is immutable and serialized with protected promotion admission by tenant/actor. */
    fun revoke(
        tenant: UUID,
        kind: SimulationAuthorityKind,
        id: UUID,
        intent: AuthorityRevocationIntent,
    ): AuthorityRevocation
}

private const val MAX_PROVENANCE = 500

class SimulationLabUnavailable : RuntimeException()

class SimulationLabConflict : RuntimeException()
