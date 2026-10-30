package org.ergon.controlplane.contracts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.adapter.inbound.http.CaseResolutionReadinessController
import org.ergon.controlplane.cases.adapter.inbound.http.ResolutionReadinessStatus
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.CaseResolutionPlan
import org.ergon.controlplane.resolution.application.PlannedResolutionStep
import org.ergon.controlplane.resolution.application.ResolutionPlanner
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.controlplane.resolution.application.ResolutionRunIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunNotReadyException
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.ergon.resolution.domain.CapabilityPolicyRule
import org.ergon.resolution.domain.ResolutionEvidence
import org.ergon.resolution.domain.ResolutionPolicy
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionReadinessEvaluator
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class DocumentaryContractReadinessTest {
    @Test
    fun `documentary readiness has no applicability next step or run even with an injected operational ready result`() {
        val tenant = UUID.randomUUID()
        val case = UUID.randomUUID()
        val contract = DocumentaryContractFixtures.decoder.decode(DocumentaryContractFixtures.document)
        val readiness = Mockito.mock(ResolutionReadinessService::class.java)
        val evaluated =
            CaseReadiness.Evaluated(
                case,
                3,
                contract,
                ResolutionReadinessEvaluator.evaluate(contract, ResolutionEvidence.of(emptyMap())),
            )
        Mockito.`when`(readiness.evaluate(tenant, case)).thenReturn(evaluated)
        val response = CaseResolutionReadinessController(readiness).evaluate(tenant, case)
        assertThat(response.status).isEqualTo(ResolutionReadinessStatus.DOCUMENTARY_EVIDENCE_UNSUPPORTED)
        assertThat(response.applicability).isNull()
        val step = contract.steps.single()
        val policy =
            ResolutionPolicy.define(
                ResolutionPolicyRevision.of("ergon.dev/policy/test/v1"),
                listOf(CapabilityPolicyRule(step.capability, step.risk, step.approval)),
            )
        val plan = ResolutionPlanningService(readiness, policy).plan(tenant, case)
        assertThat(plan.nextStep).isNull()
        val forgedReady =
            plan.copy(
                readiness =
                    evaluated.copy(
                        readiness =
                            ResolutionReadiness.Ready(
                                contract.applicability,
                                contract.applicability.expectedValue,
                            ),
                    ),
                nextStep = PlannedResolutionStep(step, policy.evaluate(step)),
            )
        for (candidate in listOf(plan, forgedReady)) assertRunBlocked(candidate, tenant, case)
    }

    private fun assertRunBlocked(
        plan: CaseResolutionPlan,
        tenant: UUID,
        case: UUID,
    ) {
        val repository = Mockito.mock(ResolutionRunRepository::class.java)
        val identities = Mockito.mock(ResolutionRunIdentityGenerator::class.java)
        val transactions =
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            }
        val service = ResolutionRunService(ResolutionPlanner { _, _ -> plan }, repository, identities, transactions)
        assertThatThrownBy { service.start(tenant, case, 3) }
            .isInstanceOfSatisfying(ResolutionRunNotReadyException::class.java) {
                assertThat(it.readiness).isEqualTo("DOCUMENTARY_EVIDENCE_UNSUPPORTED")
            }
        Mockito.verifyNoInteractions(repository, identities)
    }
}
