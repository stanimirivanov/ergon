package org.ergon.controlplane.evaluation.application

import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.CaseResolutionPlan
import org.ergon.evaluation.domain.ObservedInvocations
import org.ergon.evaluation.domain.ScenarioAssertionTrace
import org.ergon.evaluation.domain.ScenarioBudgetUsage
import org.ergon.evaluation.domain.ScenarioGrounding
import org.ergon.evaluation.domain.ScenarioObservation
import org.ergon.evaluation.domain.ScenarioProof
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.StepPolicyDecision

/** Projection never fills an observation from an expectation; unsupported stages remain unsupported. */
internal object EvaluationReplayProjection {
    fun replay(
        program: EvaluationProgram,
        invocations: ScenarioInvocationRunner,
    ): EvaluationReplayObservation {
        val result = PinnedScenarioReplay { program.pinned }.replay(byteArrayOf())
        check(result is PinnedScenarioReplayResult.Evaluated)
        val plan = result.plan
        val base = planning(program.pinned, plan)
        return when (program.kind) {
            EvaluationProgramKind.PLANNING -> {
                EvaluationReplayObservation(
                    mapOf("scenario" to base),
                    listOf(snapshot("scenario", "PLANNING", base)),
                    null,
                )
            }

            EvaluationProgramKind.CAPABILITY -> {
                capability(program, invocations, base)
            }

            EvaluationProgramKind.MODEL -> {
                model(program, base)
            }
        }
    }

    private fun planning(
        input: PinnedScenarioInput,
        plan: CaseResolutionPlan,
    ): ScenarioAssertionTrace {
        val ready = (plan.readiness as? CaseReadiness.Evaluated)?.readiness is ResolutionReadiness.Ready
        val outcome =
            when {
                !ready -> {
                    available(SimulationExpectedOutcome.NOT_READY)
                }

                plan.nextStep?.policyDecision is StepPolicyDecision.Denied -> {
                    available(SimulationExpectedOutcome.POLICY_DENIED)
                }

                else -> {
                    ScenarioObservation.Unsupported
                }
            }
        val facts =
            input.history
                .filterIsInstance<AccountAccessStateBound>()
                .lastOrNull()
                ?.factId
                ?.let(::listOf)
                .orEmpty()
        return ScenarioAssertionTrace(
            outcome = outcome,
            policy = plan.nextStep?.policyDecision?.let(::available) ?: ScenarioObservation.Unsupported,
            grounding = available(ScenarioGrounding(facts, input.documentary.map { it.pin.reference })),
            invocations = ScenarioObservation.Unsupported,
            proof = ScenarioObservation.Unsupported,
            escalation = ScenarioObservation.Unsupported,
        )
    }

    private fun capability(
        program: EvaluationProgram,
        invocations: ScenarioInvocationRunner,
        base: ScenarioAssertionTrace,
    ): EvaluationReplayObservation {
        val input = checkNotNull(program.capability)
        val result = CapabilityScenarioReplay({ input }, invocations).replay(byteArrayOf())
        if (result is CapabilityScenarioResult.Unsupported) {
            val unsupported = base.copy(outcome = ScenarioObservation.Unsupported)
            return EvaluationReplayObservation(
                mapOf("scenario" to unsupported),
                listOf(snapshot("scenario", "UNSUPPORTED:${result.reason}", unsupported)),
                null,
            )
        }
        check(result is CapabilityScenarioResult.Evaluated)
        val trace = result.trace
        val outcome =
            when (trace.status) {
                CapabilityScenarioStatus.NOT_READY -> {
                    available(SimulationExpectedOutcome.NOT_READY)
                }

                CapabilityScenarioStatus.POLICY_DENIED -> {
                    available(SimulationExpectedOutcome.POLICY_DENIED)
                }

                CapabilityScenarioStatus.WAITING_FOR_APPROVAL -> {
                    available(SimulationExpectedOutcome.WAITING_FOR_APPROVAL)
                }

                CapabilityScenarioStatus.ACTION_FAILED -> {
                    available(SimulationExpectedOutcome.FAILED)
                }

                else -> {
                    ScenarioObservation.Unsupported
                }
            }
        check(trace.idempotencyKeys.size == trace.attempts.size)
        val calls = trace.attempts.map { checkNotNull(input.route).capability }
        val projected =
            base.copy(
                outcome = outcome,
                invocations = available(ObservedInvocations(calls)),
                proof =
                    available(
                        ScenarioProof(trace.proof, trace.receipt, input.pinned.fixture.casePrefix.streamVersion),
                    ),
            )
        val details = snapshot("scenario", trace.status.name, projected).values.toMutableMap()
        trace.attempts.forEachIndexed { i, attempt ->
            details["attempt.$i.outcome"] = attempt.outcome.name
            details["attempt.$i.startedAt"] = attempt.timing.startedAt.toString()
            details["attempt.$i.completedAt"] = attempt.completedAt.toString()
            details["attempt.$i.nanoseconds"] = attempt.timing.elapsedNanoseconds.toString()
            details["attempt.$i.idempotencyKey"] = trace.idempotencyKeys[i].toString()
        }
        val usage = program.budget?.connectorPricing?.let { ScenarioBudgetAccounting.capability(trace, it) }
        return EvaluationReplayObservation(
            mapOf("scenario" to projected),
            listOf(EvaluationObservedTarget("scenario", details)),
            usage,
        )
    }

    private fun model(
        program: EvaluationProgram,
        base: ScenarioAssertionTrace,
    ): EvaluationReplayObservation {
        val result = ModelProposalScenario { checkNotNull(program.model) }.replay(byteArrayOf())
        check(result is ModelProposalResult.Evaluated)
        val traces = linkedMapOf("scenario" to base)
        val observed = mutableListOf(snapshot("scenario", "MODEL", base))
        result.observations.forEach { response ->
            val suggestion = response.suggestion
            val trace =
                base.copy(
                    grounding =
                        if (suggestion == null) {
                            ScenarioObservation.Error
                        } else {
                            available(ScenarioGrounding(suggestion.factIds, suggestion.documentary))
                        },
                )
            traces[response.script.key] = trace
            val details = snapshot(response.script.key, response.status.name, trace).values.toMutableMap()
            details["response.sha256"] = response.responseSha256
            details["script.sha256"] = response.script.sha256
            details["script.revision"] = response.script.revision
            if (suggestion != null) {
                details["suggested.step"] = suggestion.step.value
                details["suggested.capability"] = suggestion.capability.value
            }
            observed.add(EvaluationObservedTarget(response.script.key, details))
        }
        val usage =
            program.budget?.modelPricing?.let {
                ScenarioBudgetAccounting.model(result, program.budget.modelUsage, it)
            }
        return EvaluationReplayObservation(traces, observed, usage)
    }

    private fun <T> available(value: T): ScenarioObservation<T> = ScenarioObservation.Available(value)

    private fun snapshot(
        target: String,
        status: String,
        trace: ScenarioAssertionTrace,
    ): EvaluationObservedTarget {
        val values = linkedMapOf("status" to status)
        values["outcome.state"] = state(trace.outcome)
        values["calls.state"] = state(trace.invocations)
        values["policy.state"] = state(trace.policy)
        values["grounding.state"] = state(trace.grounding)
        values["proof.state"] = state(trace.proof)
        values["escalation.state"] = state(trace.escalation)
        values["outcome"] = (trace.outcome as? ScenarioObservation.Available)?.value?.name ?: "UNSUPPORTED"
        (trace.policy as? ScenarioObservation.Available)?.value?.let { policy ->
            when (policy) {
                is StepPolicyDecision.Denied -> {
                    values["policy.denied"] = policy.reason.name
                }

                is StepPolicyDecision.Requirements -> {
                    values["policy.risk"] = policy.effectiveRisk.name
                    values["policy.approval"] = policy.requiredApproval.name
                }
            }
        }
        (trace.invocations as? ScenarioObservation.Available)?.value?.let {
            values["calls"] = it.capabilities.joinToString(",") { name -> name.value }
        }
        (trace.grounding as? ScenarioObservation.Available)?.value?.let {
            values["facts"] = it.factIds.sortedBy { id -> id.toString() }.joinToString(",")
            values["documentary"] =
                it.documentary
                    .sortedBy { pin -> pin.bundleId.toString() }
                    .joinToString(",") { pin -> "${pin.bundleId}:${pin.sha256}" }
        }
        (trace.proof as? ScenarioObservation.Available)?.value?.let { proof -> describeProof(proof, values) }
        return EvaluationObservedTarget(target, values)
    }

    private fun state(observation: ScenarioObservation<*>): String =
        when (observation) {
            is ScenarioObservation.Available -> "AVAILABLE"
            ScenarioObservation.Missing -> "MISSING"
            ScenarioObservation.Unsupported -> "UNSUPPORTED"
            ScenarioObservation.Error -> "ERROR"
        }

    private fun describeProof(
        proof: ScenarioProof,
        values: MutableMap<String, String>,
    ) {
        values["proof.runPrefix"] = proof.runCaseStreamVersion.toString()
        proof.receipt?.let {
            values["receipt.outcome"] = it.outcome.name
            values["receipt.completedAt"] = it.completedAt.toString()
            values["receipt.capability"] = it.capability.value
            values["receipt.consumption"] = it.authorizationConsumptionId.value.toString()
            values["receipt.grant"] = it.authorizationGrantId.value.toString()
            values["receipt.run"] = it.runId.value.toString()
        }
        when (val assessment = proof.assessment) {
            null -> {
                values["proof.status"] = "NOT_ASSESSED"
            }

            is org.ergon.resolution.domain.ResolutionOutcomeProofAssessment.Pending -> {
                values["proof.status"] = "PENDING"
                values["proof.reason"] = assessment.reason.name
            }

            is org.ergon.resolution.domain.ResolutionOutcomeProofAssessment.Accepted -> {
                values["proof.status"] = "ACCEPTED"
                values["proof.fact"] =
                    assessment.evidence.factId.value
                        .toString()
                values["proof.observation"] =
                    assessment.evidence.observationId.value
                        .toString()
                values["proof.observedAt"] = assessment.evidence.observedAt.toString()
            }
        }
        val evidence =
            when (val assessment = proof.assessment) {
                is org.ergon.resolution.domain.ResolutionOutcomeProofAssessment.Accepted -> assessment.evidence
                is org.ergon.resolution.domain.ResolutionOutcomeProofAssessment.Pending -> assessment.evidence
                null -> null
            }
        evidence?.let {
            values["proof.evidence.value"] = it.value.value
            values["proof.evidence.observationVersion"] = it.observationStreamVersion.toString()
            values["proof.evidence.factVersion"] = it.factStreamVersion.toString()
            values["proof.evidence.boundAt"] = it.boundAt.toString()
        }
        proof.assessment?.let {
            values["proof.condition.fact"] = it.condition.fact.value
            values["proof.condition.value"] = it.condition.expectedValue.value
            values["proof.caseVersion"] = it.caseStreamVersion.toString()
        }
    }
}
