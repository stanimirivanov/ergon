package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.DocumentaryEvidenceEvaluator
import org.ergon.resolution.domain.DocumentaryRequirementAssessment
import org.ergon.resolution.domain.ResolutionEvidence
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionReadinessEvaluator
import java.util.UUID

/** Latest supported binding at a fixed prefix, not a first-page history or mutable current-case value. */
data class PinnedReadinessEvidence(
    val caseId: UUID,
    val caseStreamVersion: Long,
    val fact: PinnedAccountAccessFact?,
) {
    init {
        check(caseStreamVersion > 0)
        check(fact == null || fact.streamVersion in 1..caseStreamVersion)
    }
}

/**
 * Case-owned bounded selection: one case observation, at most one binding and only its exact source.
 * Select by descending stream version, never timestamps; absence of a binding is a successful null fact.
 * Missing/out-of-range tenant case returns null; malformed attribution and storage failures propagate.
 * The caller must establish current supervisor/resolver authority before invoking this port.
 */
fun interface PinnedReadinessEvidenceReader {
    fun findLatest(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): PinnedReadinessEvidence?
}

/**
 * Re-evaluation of immutable inputs with current evaluator semantics,
 * never a persisted historical decision or grant.
 */
data class RunPinnedReadiness(
    val pin: RunPinnedContract,
    val readiness: ResolutionReadiness,
    val selectedEvidence: List<PinnedAccountAccessFact>,
    val documentaryEvidence: List<DocumentaryRequirementAssessment> = emptyList(),
)

/** Separates readiness at the run's immutable evidence boundary from current-case planning and outcome verification. */
class RunPinnedReadinessService(
    private val pinnedContracts: RunPinnedContractService,
    private val evidence: PinnedReadinessEvidenceReader,
    private val transactions: TransactionRunner,
    private val documentary: PinnedDocumentaryEvidenceReader,
) {
    /**
     * Reuses exact current supervisor/resolver gating before private evidence access.
     * Missing requirements precede applicability; equality and stream-order selection
     * use the existing domain evaluator. Only supported facts required by this contract
     * and their selected citations are returned. No current-case or latest-contract fallback.
     *
     * @throws AssignedRunNotFoundException when current run access is denied.
     * @throws IllegalStateException when immutable pin, case boundary or provenance cannot be recovered.
     * Storage failures propagate, never become missing evidence. No remote calls or writes occur.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunPinnedReadiness =
        transactions.required {
            val pin = pinnedContracts.get(tenantId, runId, actorId)
            val snapshot =
                checkNotNull(
                    evidence.findLatest(TenantId(tenantId), pin.caseId, pin.caseStreamVersion),
                ) { "pinned readiness evidence cannot be recovered" }
            check(snapshot.caseId == pin.caseId.value && snapshot.caseStreamVersion == pin.caseStreamVersion) {
                "pinned readiness boundary is inconsistent"
            }
            val contract = pin.revision.contract
            val selected = snapshot.fact?.takeIf { ACCOUNT_ACCESS_STATE in contract.requiredEvidence }
            val values =
                selected?.let {
                    mapOf(ACCOUNT_ACCESS_STATE to ContractFactValue.of(it.value.name))
                } ?: emptyMap()
            val bundles =
                if (contract.documentaryEvidence.isEmpty()) {
                    emptyList()
                } else {
                    documentary.find(TenantId(tenantId), pin.caseId, pin.caseStreamVersion)
                }
            RunPinnedReadiness(
                pin,
                ResolutionReadinessEvaluator.evaluate(contract, ResolutionEvidence.of(values), bundles),
                listOfNotNull(selected),
                DocumentaryEvidenceEvaluator.assess(contract.documentaryEvidence, bundles),
            )
        }
}

private val ACCOUNT_ACCESS_STATE = ContractFactType.of("account.access.state")
