package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationFixture
import java.time.Instant
import java.util.UUID

/** Registration identity is separate from semantic digest; a revision cannot be replaced. */
data class SimulationFixtureIdentity(
    val tenantId: UUID,
    val key: String,
    val revision: String,
) {
    init {
        SimulationArtifact(key, revision, "0".repeat(SHA256_HEX_LENGTH))
    }
}

/** Validated immutable package; normalized definition includes provenance and every referenced material. */
data class PreparedSimulationFixture(
    val fixture: SimulationFixture,
    val definition: String,
)

/** Original actor and database timestamp survive exact retries, including retries by another registered actor. */
data class StoredSimulationFixture(
    val identity: SimulationFixtureIdentity,
    val digest: String,
    val definition: String,
    val submittedBy: UUID,
    val recordedAt: Instant,
)

data class SimulationFixtureRegistration(
    val stored: StoredSimulationFixture,
    val created: Boolean,
)

class InvalidSimulationFixture : RuntimeException()

class SimulationFixtureTooLarge : RuntimeException()

class SimulationFixtureConflict : RuntimeException()

class SimulationFixtureUnavailable : RuntimeException()

/** Consuming boundary for strict package parsing; it never resolves remote references or executes material. */
fun interface SimulationFixtureDecoder {
    fun decode(bytes: ByteArray): PreparedSimulationFixture
}

/** Atomic tenant-scoped storage. Missing and foreign identities are indistinguishable. */
interface SimulationFixtureRepository {
    /** Compare immutable meaning on retry; retain original metadata. Unknown tenant actors are unavailable. */
    fun register(
        identity: SimulationFixtureIdentity,
        prepared: PreparedSimulationFixture,
        actor: UUID,
    ): SimulationFixtureRegistration

    fun find(identity: SimulationFixtureIdentity): StoredSimulationFixture?
}

/** Internal trusted-machine use case; claimed actor attribution is not human authorization. */
class SimulationFixtureService(
    private val decoder: SimulationFixtureDecoder,
    private val repository: SimulationFixtureRepository,
) {
    fun register(
        identity: SimulationFixtureIdentity,
        actor: UUID,
        bytes: ByteArray,
    ): SimulationFixtureRegistration {
        if (bytes.size > MAX_BYTES) throw SimulationFixtureTooLarge()
        val prepared = decoder.decode(bytes)
        if (prepared.fixture.tenantId != identity.tenantId) throw InvalidSimulationFixture()
        return repository.register(identity, prepared, actor)
    }

    fun get(identity: SimulationFixtureIdentity): StoredSimulationFixture =
        repository.find(identity) ?: throw SimulationFixtureUnavailable()

    companion object {
        const val MAX_BYTES = 1_048_576
    }
}

private const val SHA256_HEX_LENGTH = 64
