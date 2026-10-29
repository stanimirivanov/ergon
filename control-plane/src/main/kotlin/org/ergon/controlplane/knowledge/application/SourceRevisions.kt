package org.ergon.controlplane.knowledge.application

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthority
import java.nio.charset.CharacterCodingException
import java.time.Clock
import java.time.Instant

/** Original attribution survives retries by another currently authorized tenant resolver. */
data class StoredSourceRevision(
    val source: EvidenceSource,
    val submittedBy: HumanActorId,
    val recordedAt: Instant,
)

/** Whether this call inserted the revision; exact recovery returns the original record. */
data class SourceRegistration(
    val stored: StoredSourceRevision,
    val created: Boolean,
)

/** Exact tenant-scoped storage; implementations atomically compare conflicting retries, never overwrite. */
interface SourceRevisionRepository {
    /** Conflicting format/content throws [SourceConflict]; infrastructure failures propagate. */
    fun register(
        source: EvidenceSource,
        actor: HumanActorId,
    ): SourceRegistration

    /** Missing and foreign revisions are indistinguishable; infrastructure failures propagate. */
    fun find(revision: SourceRevision): StoredSourceRevision?
}

class SourceConflict : RuntimeException()

class SourceUnavailable : RuntimeException()

class InvalidSource : RuntimeException()

class SourceTooLarge : RuntimeException()

/** Authenticates authority before accessing private content; acceptance supplies no operational evidence. */
class SourceRevisionService(
    private val repository: SourceRevisionRepository,
    private val authorities: HumanAuthorityRepository,
    private val clock: Clock,
) {
    companion object {
        const val MAX_BYTES = 65_536
    }

    /** Checks fresh tenant-wide resolver evidence, without disclosing whether a source exists. */
    fun requireAccess(
        revision: SourceRevision,
        actor: HumanActorId,
    ) {
        val now = clock.instant()
        val current = authorities.findCurrent(revision.tenantId, actor, ApprovalAuthority.RESOLVER, null, now)
        if (current == null) {
            throw SourceUnavailable()
        }
    }

    /** Strict UTF-8, byte limit and digest are checked before any immutable insert. No parser runs here. */
    fun register(
        revision: SourceRevision,
        actor: HumanActorId,
        format: EvidenceSourceFormat,
        bytes: ByteArray,
    ): SourceRegistration {
        requireAccess(revision, actor)
        validateSize(bytes)
        val source =
            try {
                EvidenceSource.fromUtf8(revision.tenantId, revision.sourceId, format, bytes)
            } catch (_: CharacterCodingException) {
                throw InvalidSource()
            }
        if (source.revision != revision) throw SourceConflict()
        return repository.register(source, actor)
    }

    private fun validateSize(bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) throw SourceTooLarge()
    }

    /** Reads only the exact revision after fresh authority; no continuous revocation guarantee. */
    fun get(
        revision: SourceRevision,
        actor: HumanActorId,
    ): StoredSourceRevision {
        requireAccess(revision, actor)
        return repository.find(revision) ?: throw SourceUnavailable()
    }
}
