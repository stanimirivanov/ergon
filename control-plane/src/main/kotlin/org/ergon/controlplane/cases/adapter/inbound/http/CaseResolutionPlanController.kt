package org.ergon.controlplane.cases.adapter.inbound.http

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.controlplane.resolution.application.CaseResolutionPlan
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.resolution.domain.StepPolicyDecision
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Internal query adapter for the policy requirements of a case's next step. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-plan")
class CaseResolutionPlanController(
    private val service: ResolutionPlanningService,
) {
    @GetMapping
    fun plan(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
    ): CaseResolutionPlanResponse = service.plan(tenantId, caseId).toResponse()
}

/**
 * Evidence readiness and policy requirements evaluated for one case snapshot.
 *
 * [nextStep] is absent until [readiness] is `READY`. Its presence still does
 * not establish actor authority or permit capability invocation.
 */
data class CaseResolutionPlanResponse(
    val readiness: CaseResolutionReadinessResponse,
    val policyRevision: String,
    val nextStep: PlannedResolutionStepResponse?,
)

/** Contract declaration and effective policy requirements for one ordered step. */
data class PlannedResolutionStepResponse(
    val id: String,
    val capability: String,
    val declaredRisk: String,
    val declaredApproval: String,
    val decision: StepPolicyDecisionResponse,
    val effectiveRisk: String?,
    val requiredApproval: String?,
    val denialReason: String?,
)

/** Whether policy denies a step or requires human approval before later authorization. */
enum class StepPolicyDecisionResponse {
    DENIED,
    HUMAN_APPROVAL_REQUIRED,
    NO_HUMAN_APPROVAL_REQUIRED,
}

private fun CaseResolutionPlan.toResponse() =
    CaseResolutionPlanResponse(
        readiness = readiness.toResponse(),
        policyRevision = policyRevision.value,
        nextStep =
            nextStep?.let { planned ->
                val step = planned.step
                when (val decision = planned.policyDecision) {
                    is StepPolicyDecision.Requirements -> {
                        PlannedResolutionStepResponse(
                            id = step.id.value,
                            capability = step.capability.value,
                            declaredRisk = step.risk.name,
                            declaredApproval = step.approval.name,
                            decision = decision.toResponse(),
                            effectiveRisk = decision.effectiveRisk.name,
                            requiredApproval = decision.requiredApproval.name,
                            denialReason = null,
                        )
                    }

                    is StepPolicyDecision.Denied -> {
                        PlannedResolutionStepResponse(
                            id = step.id.value,
                            capability = step.capability.value,
                            declaredRisk = step.risk.name,
                            declaredApproval = step.approval.name,
                            decision = StepPolicyDecisionResponse.DENIED,
                            effectiveRisk = null,
                            requiredApproval = null,
                            denialReason = decision.reason.name,
                        )
                    }
                }
            },
    )

private fun StepPolicyDecision.Requirements.toResponse(): StepPolicyDecisionResponse =
    if (requiredApproval == ApprovalRequirement.NONE) {
        StepPolicyDecisionResponse.NO_HUMAN_APPROVAL_REQUIRED
    } else {
        StepPolicyDecisionResponse.HUMAN_APPROVAL_REQUIRED
    }
