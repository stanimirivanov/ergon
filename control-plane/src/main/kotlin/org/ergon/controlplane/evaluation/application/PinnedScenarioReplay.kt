package org.ergon.controlplane.evaluation.application

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.NewCaseEvent
import org.ergon.controlplane.cases.application.StoredCaseEvent
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.controlplane.resolution.application.CaseResolutionPlan
import org.ergon.controlplane.resolution.application.PinnedDocumentaryEvidenceReader
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.evaluation.domain.SimulationFixture
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.PinnedDocumentaryBundle
import org.ergon.resolution.domain.ResolutionPolicy

/** Fully validated material; the decoder owns identity, prefix and documentary attachment consistency checks. */
data class PinnedScenarioInput(
    val fixture: SimulationFixture,
    val contract: ResolutionContract,
    val policy: ResolutionPolicy,
    val history: List<CaseEvent>,
    val documentary: List<PinnedDocumentaryBundle>,
)

fun interface PinnedScenarioDecoder {
    fun decode(bytes: ByteArray): PinnedScenarioInput
}

enum class UnsupportedScenarioReason { EVALUATOR, MATERIAL_VERSION, EVENT, EXECUTION_SCRIPT, CONTRACT_VOCABULARY }

class UnsupportedPinnedScenario(
    val reason: UnsupportedScenarioReason,
) : RuntimeException()

/** A planning observation is not an assertion pass, accepted proof, execution grant or promotion decision. */
sealed interface PinnedScenarioReplayResult {
    data class Evaluated(
        val fixtureDigest: String,
        val plan: CaseResolutionPlan,
    ) : PinnedScenarioReplayResult

    data class Unsupported(
        val reason: UnsupportedScenarioReason,
    ) : PinnedScenarioReplayResult
}

/** Runs the production planning services against isolated read-only material; no clock, ID generator or I/O ports. */
class PinnedScenarioReplay(
    private val decoder: PinnedScenarioDecoder,
) {
    /** Invalid material throws [InvalidSimulationFixture]; unsupported behavior never passes evaluation. */
    fun replay(bytes: ByteArray): PinnedScenarioReplayResult =
        try {
            val input = decoder.decode(bytes)
            val readiness =
                ResolutionReadinessService(
                    ScenarioEvents(input),
                    ScenarioContract(input),
                    PinnedDocumentaryEvidenceReader { tenant, case, version ->
                        require(tenant.value == input.fixture.tenantId && case.value == input.fixture.casePrefix.caseId)
                        require(version == input.fixture.casePrefix.streamVersion)
                        input.documentary
                    },
                )
            val plan =
                ResolutionPlanningService(readiness, input.policy)
                    .plan(input.fixture.tenantId, input.fixture.casePrefix.caseId)
            PinnedScenarioReplayResult.Evaluated(input.fixture.semanticDigest(), plan)
        } catch (unsupported: UnsupportedPinnedScenario) {
            PinnedScenarioReplayResult.Unsupported(unsupported.reason)
        }
}

private class ScenarioEvents(
    private val input: PinnedScenarioInput,
) : CaseEventStore {
    override fun load(
        tenantId: TenantId,
        caseId: CaseId,
    ): List<CaseEvent> {
        require(tenantId.value == input.fixture.tenantId && caseId.value == input.fixture.casePrefix.caseId)
        return input.history
    }

    override fun append(
        tenantId: TenantId,
        caseId: CaseId,
        expectedVersion: Long,
        events: List<NewCaseEvent>,
    ): List<StoredCaseEvent> = error("simulation planning cannot append production events")
}

private class ScenarioContract(
    private val input: PinnedScenarioInput,
) : ResolutionContractRevisionRepository {
    override fun find(
        tenantId: TenantId,
        key: ResolutionContractKey,
        revision: ResolutionContractRevision,
    ): StoredResolutionContractRevision? {
        require(tenantId.value == input.fixture.tenantId)
        return if (key == input.contract.key && revision == input.contract.revision) {
            StoredResolutionContractRevision(input.contract, input.fixture.evaluationAt)
        } else {
            null
        }
    }

    override fun publish(
        tenantId: TenantId,
        contract: ResolutionContract,
    ): StoredResolutionContractRevision = error("simulation planning cannot publish production contracts")
}
