package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.ResolutionStep
import org.ergon.resolution.domain.ResolutionPolicy
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.StepPolicyDecision
import java.util.UUID

/** Policy requirements for the first ordered step of a ready contract. */
data class PlannedResolutionStep(
    val step: ResolutionStep,
    val policyDecision: StepPolicyDecision,
)

/**
 * Resolution planning result at the case version carried by [readiness].
 *
 * [nextStep] is present only when evidence readiness is [ResolutionReadiness.Ready].
 */
data class CaseResolutionPlan(
    val readiness: CaseReadiness,
    val policyRevision: ResolutionPolicyRevision,
    val nextStep: PlannedResolutionStep?,
)

/** Applies one immutable policy revision to the next step of a ready contract. */
class ResolutionPlanningService(
    private val readinessService: ResolutionReadinessService,
    private val policy: ResolutionPolicy,
) {
    /**
     * Evaluates readiness and, when ready, policy requirements for the first contract step.
     *
     * This query does not grant an approval, establish actor authority, persist
     * a run, or invoke the capability.
     */
    fun plan(
        tenantId: UUID,
        caseId: UUID,
    ): CaseResolutionPlan {
        val readiness = readinessService.evaluate(tenantId, caseId)
        val nextStep =
            if (readiness is CaseReadiness.Evaluated && readiness.readiness is ResolutionReadiness.Ready) {
                val step = readiness.contract.steps.first()
                PlannedResolutionStep(step, policy.evaluate(step))
            } else {
                null
            }
        return CaseResolutionPlan(readiness, policy.revision, nextStep)
    }
}
