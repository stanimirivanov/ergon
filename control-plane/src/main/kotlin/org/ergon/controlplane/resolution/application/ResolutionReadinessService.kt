package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.CaseNotFoundException
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionEvidence
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionReadinessEvaluator
import java.util.UUID

/** Readiness snapshot for one case stream version. */
sealed interface CaseReadiness {
    val caseId: UUID
    val caseStreamVersion: Long

    /** No contract has been selected, so evidence must not start a run. */
    data class WaitingForContract(
        override val caseId: UUID,
        override val caseStreamVersion: Long,
    ) : CaseReadiness

    /** Result of evaluating the exact [contract] against facts in the case snapshot. */
    data class Evaluated(
        override val caseId: UUID,
        override val caseStreamVersion: Long,
        val contract: ResolutionContractIdentity,
        val readiness: ResolutionReadiness,
    ) : CaseReadiness
}

/** Evaluates whether a case's pinned contract has enough evidence to apply. */
class ResolutionReadinessService(
    private val eventStore: CaseEventStore,
    private val contractRevisions: ResolutionContractRevisionRepository,
) {
    /**
     * Evaluates the exact case history visible at one stream version.
     *
     * The latest bound value for each fact type is authoritative within that
     * snapshot. This query does not pin a contract, authorize a step, or create
     * execution state.
     *
     * @throws CaseNotFoundException when the case is absent from the tenant boundary.
     * @throws IllegalStateException when a pinned immutable revision cannot be recovered.
     */
    fun evaluate(
        tenantId: UUID,
        caseId: UUID,
    ): CaseReadiness {
        val scopedTenantId = TenantId(tenantId)
        val scopedCaseId = CaseId(caseId)
        val history = eventStore.load(scopedTenantId, scopedCaseId)
        if (history.isEmpty()) {
            throw CaseNotFoundException(tenantId, caseId)
        }
        val case = ErgonCase.rehydrate(scopedCaseId, scopedTenantId, history)
        val contractIdentity =
            case.pinnedResolutionContract
                ?: return CaseReadiness.WaitingForContract(caseId, case.streamVersion)
        val contract =
            contractRevisions.find(scopedTenantId, contractIdentity.key, contractIdentity.revision)?.contract
                ?: error("pinned resolution contract revision cannot be recovered")

        return CaseReadiness.Evaluated(
            caseId = caseId,
            caseStreamVersion = case.streamVersion,
            contract = contractIdentity,
            readiness = ResolutionReadinessEvaluator.evaluate(contract, history.toEvidence()),
        )
    }
}

private fun List<org.ergon.cases.domain.CaseEvent>.toEvidence(): ResolutionEvidence {
    val values = mutableMapOf<ContractFactType, ContractFactValue>()
    forEach { event ->
        if (event is AccountAccessStateBound) {
            values[ACCOUNT_ACCESS_STATE] = ContractFactValue.of(event.state.name)
        }
    }
    return ResolutionEvidence.of(values)
}

private val ACCOUNT_ACCESS_STATE = ContractFactType.of("account.access.state")
