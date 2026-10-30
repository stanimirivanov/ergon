package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceBundleManifest
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StoredEvidenceBundle(
    val command: EvidenceBundleCommand,
    val manifest: EvidenceBundleManifest,
    val sha256: String,
    val publisher: HumanActorId,
    val authority: ApprovalAuthorityEvidenceId,
    val recordedAt: Instant,
)

data class EvidenceBundleRegistration(
    val stored: StoredEvidenceBundle,
    val created: Boolean,
)

class InvalidEvidenceBundle : RuntimeException()

class EvidenceBundleConflict : RuntimeException()

/** Atomically revalidates complete claim sets and immutable references; retries retain historical content. */
interface EvidenceBundleRepository {
    fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredEvidenceBundle?

    fun publish(
        admission: EvidenceReviewAdmission,
        command: EvidenceBundleCommand,
        content: EvidenceBundleContent,
    ): EvidenceBundleRegistration
}

/** Current resolver checks gate publication, history and receipt recovery; history never selects current evidence. */
class EvidenceBundleService(
    private val authorities: HumanAuthorityRepository,
    private val preparer: EvidenceBundlePreparer,
    private val repository: EvidenceBundleRepository,
    private val clock: Clock,
) {
    fun publish(
        tenant: TenantId,
        actor: HumanActorId,
        command: EvidenceBundleCommand,
    ): EvidenceBundleRegistration {
        val admission = authorize(tenant, actor)
        val previous = repository.find(tenant, command.id)
        val content =
            if (previous == null) {
                preparer.prepare(tenant, actor, command)
            } else {
                if (previous.command != command || previous.publisher != actor) throw EvidenceBundleConflict()
                previous.manifest.content
            }
        return repository.publish(admission, command, content)
    }

    /** Returns the original cutoff and manifest even after new acceptances; only access is reauthorized. */
    fun get(
        tenant: TenantId,
        actor: HumanActorId,
        id: UUID,
    ): StoredEvidenceBundle {
        authorize(tenant, actor)
        return repository.find(tenant, id) ?: throw SourceUnavailable()
    }

    private fun authorize(
        tenant: TenantId,
        actor: HumanActorId,
    ): EvidenceReviewAdmission {
        val proof =
            authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
                ?: throw SourceUnavailable()
        return EvidenceReviewAdmission(tenant, actor, proof.evidence.id)
    }
}
