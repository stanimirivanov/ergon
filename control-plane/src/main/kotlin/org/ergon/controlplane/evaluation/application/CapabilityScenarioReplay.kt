package org.ergon.controlplane.evaluation.application

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.toRunPlan
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ApprovalDecision
import org.ergon.resolution.domain.ApprovalDecisionBasis
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionBasis
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrant
import org.ergon.resolution.domain.CapabilityAuthorizationGrantBasis
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityRoute
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.StepPolicyDecision
import java.util.UUID

/** Bounded simulation only: real domain gates and invocation service, no production repositories or live gateway. */
class CapabilityScenarioReplay(
    private val decoder: CapabilityScenarioDecoder,
    private val invocations: ScenarioInvocationRunner,
) {
    fun replay(bytes: ByteArray): CapabilityScenarioResult =
        try {
            evaluate(decoder.decode(bytes))
        } catch (unsupported: UnsupportedPinnedScenario) {
            CapabilityScenarioResult.Unsupported(unsupported.reason.name)
        }

    @Suppress("ReturnCount") // Each rejected gate stops before later authority or execution stages.
    private fun evaluate(input: CapabilityScenario): CapabilityScenarioResult {
        val digest = input.pinned.fixture.semanticDigest()

        fun stop(status: CapabilityScenarioStatus): CapabilityScenarioResult =
            CapabilityScenarioResult.Evaluated(CapabilityScenarioTrace(digest, status))
        val planner = PinnedScenarioReplay { input.pinned }
        val planning = planner.replay(byteArrayOf()) as PinnedScenarioReplayResult.Evaluated
        val readiness = planning.plan.readiness
        if (readiness !is CaseReadiness.Evaluated || readiness.readiness !is ResolutionReadiness.Ready) {
            return stop(CapabilityScenarioStatus.NOT_READY)
        }
        val step = checkNotNull(planning.plan.nextStep)
        if (step.policyDecision is StepPolicyDecision.Denied) return stop(CapabilityScenarioStatus.POLICY_DENIED)
        val run =
            ResolutionRunStart.create(
                ResolutionRunId(simulationId(digest, "run")),
                CaseId(input.pinned.fixture.casePrefix.caseId),
                planning.plan.toRunPlan(input.pinned.fixture.casePrefix.streamVersion),
            )
        if (run.requiredApproval == ApprovalRequirement.NONE) {
            return CapabilityScenarioResult.Unsupported("NO_APPROVAL_EXECUTION_PATH")
        }
        if (input.authority == null || input.decision == null) {
            return stop(CapabilityScenarioStatus.WAITING_FOR_APPROVAL)
        }
        val request =
            ApprovalRequest.create(
                ApprovalRequestId(simulationId(digest, "request")),
                run,
                input.pinned.fixture.evaluationAt,
                input.requestLifetime,
            )
        val decision =
            try {
                decision(input, run, request)
            } catch (_: IllegalArgumentException) {
                return stop(CapabilityScenarioStatus.AUTHORITY_REJECTED)
            }
        if (decision.outcome == ApprovalDecisionOutcome.REJECTED) {
            return stop(CapabilityScenarioStatus.APPROVAL_REJECTED)
        }
        val route = input.route ?: return stop(CapabilityScenarioStatus.ROUTE_UNAVAILABLE)
        val consumption =
            try {
                val grant =
                    CapabilityAuthorizationGrant.derive(
                        CapabilityAuthorizationGrantId(simulationId(digest, "grant")),
                        CapabilityAuthorizationGrantBasis(run, request, decision),
                        input.pinned.fixture.evaluationAt,
                    )
                CapabilityAuthorizationConsumption.consume(
                    CapabilityAuthorizationConsumptionId(simulationId(digest, "consumption")),
                    CapabilityAuthorizationConsumptionBasis(grant, CapabilityRoute(route.capability, route.connector)),
                    input.pinned.fixture.evaluationAt
                        .plusSeconds(input.consumeAfterSeconds),
                )
            } catch (_: IllegalArgumentException) {
                return stop(CapabilityScenarioStatus.AUTHORIZATION_REJECTED)
            }
        return CapabilityScenarioResult.Evaluated(invocations.execute(input, consumption))
    }

    private fun decision(
        input: CapabilityScenario,
        run: ResolutionRunStart,
        request: ApprovalRequest,
    ): ApprovalDecision {
        val authority = checkNotNull(input.authority)
        val at = input.pinned.fixture.evaluationAt
        val digest = input.pinned.fixture.semanticDigest()
        val evidence =
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(simulationId(digest, "authority")),
                HumanActorId(authority.actor),
                authority.authority,
                authority.caseId?.let(::CaseId),
                ApprovalAuthorityEvidenceSource.create("simulation", digest),
                at.plusSeconds(authority.fromSeconds),
                at.plusSeconds(authority.untilSeconds),
            )
        return ApprovalDecision.record(
            ApprovalDecisionId(simulationId(digest, "decision")),
            ApprovalDecisionBasis(request, run.caseId, evidence),
            checkNotNull(input.decision),
            at,
        )
    }
}

/** Name-based simulation identity; never a production identity generator. */
internal fun simulationId(
    digest: String,
    label: String,
): UUID = UUID.nameUUIDFromBytes("$digest:$label".toByteArray(Charsets.UTF_8))
