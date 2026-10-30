package org.ergon.controlplane.evaluation.application

import org.ergon.contracts.domain.ResolutionContract
import org.ergon.evaluation.domain.EvaluationGatePolicy
import org.ergon.evaluation.domain.EvaluationSuite
import org.ergon.evaluation.domain.SimulationArtifact
import java.time.Instant
import java.util.UUID

/** Fixed storage kinds, never a caller-provided SQL table name. */
enum class GateDocumentKind { POLICY, SUITE }

data class GateDocumentIdentity(
    val tenant: UUID,
    val key: String,
    val revision: String,
) {
    init {
        SimulationArtifact(key, revision, "0".repeat(SHA256_LENGTH))
    }
}

/** One normalized, versioned definition; exactly one parsed kind is present. */
data class PreparedGateDocument(
    val kind: GateDocumentKind,
    val definition: String,
    val digest: String,
    val policy: EvaluationGatePolicy?,
    val suite: EvaluationSuite?,
)

data class StoredGateDocument(
    val identity: GateDocumentIdentity,
    val kind: GateDocumentKind,
    val digest: String,
    val definition: String,
    val submittedBy: UUID,
    val recordedAt: Instant,
) {
    fun reference() = SimulationArtifact(identity.key, identity.revision, digest)
}

data class GateDocumentRegistration(
    val stored: StoredGateDocument,
    val created: Boolean,
)

/** Strict parsing and normalized immutable definition hashing. */
interface GateDocumentCodec {
    fun decode(
        kind: GateDocumentKind,
        bytes: ByteArray,
    ): PreparedGateDocument
}

/** Atomic insert/retry with tenant-safe references; changed content never overwrites a revision. */
interface GateDocumentRepository {
    fun register(
        identity: GateDocumentIdentity,
        document: PreparedGateDocument,
        actor: UUID,
    ): GateDocumentRegistration

    fun find(
        kind: GateDocumentKind,
        identity: GateDocumentIdentity,
    ): StoredGateDocument?
}

/** Matches decoded fixture contract meaning against the exact published tenant revision. */
fun interface PublishedEvaluationContract {
    fun matches(
        tenant: UUID,
        contract: ResolutionContract,
    ): Boolean
}

class GateDocumentConflict : RuntimeException()

class GateDocumentUnavailable : RuntimeException()

class InvalidGateDocument : RuntimeException()

/** Registration validates immutable references without executing a scenario or granting promotion authority. */
class EvaluationSuites(
    private val documents: GateDocumentRepository,
    private val codec: GateDocumentCodec,
    private val fixtures: SimulationFixtureRepository,
    private val programs: EvaluationProgramDecoder,
    private val contracts: PublishedEvaluationContract,
) {
    fun register(
        kind: GateDocumentKind,
        identity: GateDocumentIdentity,
        bytes: ByteArray,
        actor: UUID,
    ): GateDocumentRegistration {
        val prepared = codec.decode(kind, bytes)
        prepared.suite?.let { suite ->
            val policyIdentity = identity.copy(key = suite.policy.key, revision = suite.policy.revision)
            val policy = get(GateDocumentKind.POLICY, policyIdentity)
            if (policy.digest != suite.policy.sha256) throw GateDocumentUnavailable()
            suite.scenarios.forEach { scenario -> validateScenario(identity.tenant, suite, scenario) }
        }
        return documents.register(identity, prepared, actor)
    }

    private fun validateScenario(
        tenant: UUID,
        suite: EvaluationSuite,
        scenario: org.ergon.evaluation.domain.RequiredScenario,
    ) {
        val fixtureIdentity = SimulationFixtureIdentity(tenant, scenario.fixture.key, scenario.fixture.revision)
        val fixture = fixtures.find(fixtureIdentity)
        if (fixture == null || fixture.digest != scenario.fixture.sha256) throw GateDocumentUnavailable()
        validateProgram(tenant, suite, scenario, programs.decode(fixture.definition.toByteArray(Charsets.UTF_8)))
    }

    private fun validateProgram(
        tenant: UUID,
        suite: EvaluationSuite,
        scenario: org.ergon.evaluation.domain.RequiredScenario,
        program: EvaluationProgram,
    ) {
        val ids = program.assertions.map { it.assertion.id } + "fixture-outcome"
        if (program.pinned.fixture.contract != suite.contract || !ids.containsAll(scenario.requiredAssertions)) {
            throw InvalidGateDocument()
        }
        if (!contracts.matches(tenant, program.pinned.contract)) throw GateDocumentUnavailable()
    }

    fun get(
        kind: GateDocumentKind,
        identity: GateDocumentIdentity,
    ): StoredGateDocument = documents.find(kind, identity) ?: throw GateDocumentUnavailable()
}

private const val SHA256_LENGTH = 64
