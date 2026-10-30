package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.AssertionResult
import org.ergon.evaluation.domain.BudgetPricing
import org.ergon.evaluation.domain.EvaluationBudget
import org.ergon.evaluation.domain.ScenarioAssertion
import org.ergon.evaluation.domain.SimulationArtifact
import java.time.Instant
import java.util.UUID

/** The target is scenario or an exact model script key, never an expression or executable selector. */
data class TargetedAssertion(
    val target: String,
    val assertion: ScenarioAssertion,
)

data class EvaluationBudgetProgram(
    val limits: EvaluationBudget,
    val pricing: List<BudgetPricing>,
    val connectorPricing: SimulationArtifact?,
    val modelPricing: SimulationArtifact?,
    val modelUsage: List<ScriptedModelUsage>,
)

enum class EvaluationProgramKind { PLANNING, CAPABILITY, MODEL }

data class EvaluationProgram(
    val kind: EvaluationProgramKind,
    val pinned: PinnedScenarioInput,
    val capability: CapabilityScenario?,
    val model: ModelProposalInput?,
    val assertions: List<TargetedAssertion>,
    val budget: EvaluationBudgetProgram?,
)

/** Verifies material identities and parses bounded expectations; invalid or unsupported packages fail before replay. */
fun interface EvaluationProgramDecoder {
    fun decode(bytes: ByteArray): EvaluationProgram
}

/** Stable semantic strings only; raw model/source text and recording metadata are deliberately absent. */
data class EvaluationObservedTarget(
    val target: String,
    val values: Map<String, String>,
)

data class EvaluationBudgetDimension(
    val limit: Long?,
    val measured: Long?,
    val status: String,
    val reason: String?,
)

data class EvaluationBudgetSummary(
    val currency: String,
    val cost: EvaluationBudgetDimension,
    val simulatedLatency: EvaluationBudgetDimension,
)

/** Versioned report meaning. Command UUID, actor and recording instant are outside this content. */
data class EvaluationReportContent(
    val schemaVersion: String,
    val fixtureDigest: String,
    val evaluator: SimulationArtifact,
    val assertions: SimulationArtifact,
    val budget: SimulationArtifact?,
    val observed: List<EvaluationObservedTarget>,
    val checks: List<AssertionResult>,
    val budgets: EvaluationBudgetSummary?,
    val passed: Boolean,
)

data class PreparedEvaluationReport(
    val content: EvaluationReportContent,
    val definition: String,
    val digest: String,
)

/** Versioned canonical content; decoding rejects hash-independent inconsistencies and noncanonical stored bytes. */
interface EvaluationReportCodec {
    fun encode(content: EvaluationReportContent): PreparedEvaluationReport

    fun decode(definition: String): PreparedEvaluationReport
}

data class EvaluationCommand(
    val fixture: SimulationFixtureIdentity,
    val id: UUID,
)

data class StoredEvaluationReport(
    val command: EvaluationCommand,
    val fixtureDigest: String,
    val reportDigest: String,
    val definition: String,
    val passed: Boolean,
    val submittedBy: UUID,
    val recordedAt: Instant,
)

data class EvaluationReportRegistration(
    val stored: StoredEvaluationReport,
    val created: Boolean,
)

/** Tenant-scoped immutable command receipts; exact retries retain the first actor/time, changed content conflicts. */
interface EvaluationReportRepository {
    fun record(
        command: EvaluationCommand,
        report: PreparedEvaluationReport,
        actor: UUID,
    ): EvaluationReportRegistration

    fun find(
        tenant: UUID,
        id: UUID,
    ): StoredEvaluationReport?
}

class EvaluationReportConflict : RuntimeException()

class EvaluationReportUnavailable : RuntimeException()

/** Trusted-machine synchronous command. Only server execution produces a candidate report. */
class EvaluationExecution(
    private val fixtures: SimulationFixtureRepository,
    private val runner: EvaluationReportRunner,
    private val reports: EvaluationReportRepository,
) {
    /** Runs offline before atomic persistence; malformed material and unexpected failures leave no completed record. */
    fun execute(
        command: EvaluationCommand,
        actor: UUID,
    ): EvaluationReportRegistration {
        val fixture = fixtures.find(command.fixture) ?: throw EvaluationReportUnavailable()
        val existing = reports.find(command.fixture.tenantId, command.id)
        if (existing != null && existing.command != command) throw EvaluationReportConflict()
        // No database transaction spans replay. Exact retries are deterministic and compared atomically on write.
        val report = runner.execute(fixture.definition.toByteArray(Charsets.UTF_8))
        check(report.content.fixtureDigest == fixture.digest)
        return reports.record(command, report, actor)
    }

    fun get(
        tenant: UUID,
        id: UUID,
    ): StoredEvaluationReport = reports.find(tenant, id) ?: throw EvaluationReportUnavailable()
}
