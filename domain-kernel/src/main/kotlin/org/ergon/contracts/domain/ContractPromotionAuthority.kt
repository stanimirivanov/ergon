package org.ergon.contracts.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Dedicated tenant-wide promotion entitlement. It confers no requester/resolver approval authority. */
data class ContractPromotionAuthority(
    val id: UUID,
    val actorId: UUID,
    val sourceProvider: String,
    val sourceReference: String,
    val attestedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(sourceProvider.matches(Regex("[a-z0-9][a-z0-9._-]{0,99}")))
        require(sourceReference.isNotBlank() && sourceReference.length <= MAX_REFERENCE)
        require(Charsets.UTF_8.newEncoder().canEncode(sourceReference))
        require(sourceReference == sourceReference.trim() && sourceReference.none { it.isISOControl() })
        require(attestedAt.nano % NANOS_PER_MICROSECOND == 0 && expiresAt.nano % NANOS_PER_MICROSECOND == 0)
        require(expiresAt > attestedAt && Duration.between(attestedAt, expiresAt) <= MAX_LIFETIME)
    }

    /** Both boundaries matter: an attestation is current only during its half-open validity interval. */
    fun currentAt(at: Instant): Boolean = at >= attestedAt && at < expiresAt

    companion object {
        val MAX_LIFETIME: Duration = Duration.ofHours(24)
        private const val MAX_REFERENCE = 500
        private const val NANOS_PER_MICROSECOND = 1_000
    }
}
