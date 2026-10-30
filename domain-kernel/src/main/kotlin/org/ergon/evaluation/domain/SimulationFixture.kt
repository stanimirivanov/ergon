package org.ergon.evaluation.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.List.copyOf
import java.util.UUID

/** Exact immutable artifact meaning. Resolution and digest verification belong to the consuming adapter. */
data class SimulationArtifact(
    val key: String,
    val revision: String,
    val sha256: String,
) {
    init {
        require(TOKEN.matches(key)) { "invalid artifact key" }
        require(TOKEN.matches(revision)) { "invalid artifact revision" }
        require(DIGEST.matches(sha256)) { "expected lowercase SHA-256" }
    }

    private companion object {
        val TOKEN = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        val DIGEST = Regex("[0-9a-f]{64}")
    }
}

/** Exact case event prefix, including evidence bindings, represented by reviewed immutable material. */
data class SimulationCasePrefix(
    val caseId: UUID,
    val streamVersion: Long,
    val material: SimulationArtifact,
) {
    init {
        require(streamVersion > 0) { "case prefix must be nonempty" }
    }
}

/** Documentary validity is evaluated at this server-recorded attachment instant, never at replay time. */
data class SimulationDocumentaryPin(
    val bundle: SimulationArtifact,
    val streamVersion: Long,
    val attachedAt: Instant,
) {
    init {
        require(streamVersion > 0)
        requireMicroseconds(attachedAt)
    }
}

/** Script contents are data artifacts, never executable code or a live-provider fallback. */
enum class SimulationScriptKind { CONNECTOR, MODEL }

data class SimulationScript(
    val kind: SimulationScriptKind,
    val material: SimulationArtifact,
)

/** Supported expected terminal or intentionally waiting observations; none implies production authority. */
enum class SimulationExpectedOutcome {
    NOT_READY,
    POLICY_DENIED,
    WAITING_FOR_APPROVAL,
    VERIFIED,
    FAILED,
    ESCALATED,
    CANCELLED,
}

/**
 * Versioned, bounded replay manifest. Artifacts pin content, not storage locations.
 *
 * Scripts are ordered; documentary pins are an unordered set normalized by case stream version.
 * [assertions] pins independently authored assertion data, while [expectedOutcome] states the overall expectation.
 * [modelConfiguration] is required exactly when a model script is present. [budget] pins optional unit/pricing data.
 * Collections are defensively frozen. Recording IDs, actors and timestamps belong outside this semantic manifest.
 * Construction rejects unsupported versions and invalid values with [IllegalArgumentException].
 */
@Suppress("LongParameterList") // One immutable manifest pins all independent replay inputs atomically.
class SimulationFixture(
    val schemaVersion: String,
    val tenantId: UUID,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val casePrefix: SimulationCasePrefix,
    val evaluator: SimulationArtifact,
    val evaluationAt: Instant,
    documentaryPins: List<SimulationDocumentaryPin>,
    scripts: List<SimulationScript>,
    val modelConfiguration: SimulationArtifact?,
    val assertions: SimulationArtifact,
    val expectedOutcome: SimulationExpectedOutcome,
    val budget: SimulationArtifact? = null,
) {
    val documentaryPins: List<SimulationDocumentaryPin>
    val scripts: List<SimulationScript>

    init {
        require(schemaVersion == VERSION) { "unsupported simulation fixture version" }
        require(documentaryPins.size <= MAX_DOCUMENTARY_PINS) { "too many documentary pins" }
        require(scripts.size <= MAX_SCRIPTS) { "too many scripts" }
        requireMicroseconds(evaluationAt)
        require(documentaryPins.map { it.streamVersion }.distinct().size == documentaryPins.size)
        require(documentaryPins.map { it.bundle.key }.distinct().size == documentaryPins.size)
        require(documentaryPins.all { it.streamVersion <= casePrefix.streamVersion && it.attachedAt <= evaluationAt })
        require(scripts.map { it.material.key }.distinct().size == scripts.size) { "duplicate script identity" }
        require((modelConfiguration != null) == scripts.any { it.kind == SimulationScriptKind.MODEL })
        this.documentaryPins = copyOf(documentaryPins.sortedBy { it.streamVersion })
        this.scripts = copyOf(scripts)
        require(canonicalBytes().size <= MAX_CANONICAL_BYTES) { "fixture exceeds canonical byte limit" }
    }

    /** SHA-256 of the documented v1 canonical encoding, with no runtime or recording metadata. */
    fun semanticDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes())
        return HexFormat.of().formatHex(digest)
    }

    /** Fresh bytes on every call; callers cannot mutate this fixture through its canonical representation. */
    fun canonicalBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.text(schemaVersion)
            out.text(tenantId.toString())
            out.artifact(contract)
            out.artifact(policy)
            out.text(casePrefix.caseId.toString())
            out.writeLong(casePrefix.streamVersion)
            out.artifact(casePrefix.material)
            out.artifact(evaluator)
            out.instant(evaluationAt)
            out.writeInt(documentaryPins.size)
            documentaryPins.forEach { pin ->
                out.artifact(pin.bundle)
                out.writeLong(pin.streamVersion)
                out.instant(pin.attachedAt)
            }
            out.writeInt(scripts.size)
            scripts.forEach { script ->
                out.text(script.kind.name)
                out.artifact(script.material)
            }
            out.optionalArtifact(modelConfiguration)
            out.artifact(assertions)
            out.text(expectedOutcome.name)
            out.optionalArtifact(budget)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val VERSION = "ergon.simulation/v1"
        const val MAX_DOCUMENTARY_PINS = 20
        const val MAX_SCRIPTS = 100
        const val MAX_CANONICAL_BYTES = 65_536
    }
}

private const val NANOS_PER_MICROSECOND = 1_000

private fun requireMicroseconds(instant: Instant) {
    require(instant.nano % NANOS_PER_MICROSECOND == 0) { "instant must have exact microsecond precision" }
}

private fun DataOutputStream.text(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.artifact(value: SimulationArtifact) {
    text(value.key)
    text(value.revision)
    text(value.sha256)
}

private fun DataOutputStream.optionalArtifact(value: SimulationArtifact?) {
    writeBoolean(value != null)
    if (value != null) artifact(value)
}

private fun DataOutputStream.instant(value: Instant) {
    writeLong(value.epochSecond)
    writeInt(value.nano)
}
