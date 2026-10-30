package org.ergon.controlplane.evaluation.adapter.out.simulation

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.evaluation.application.CapabilityScenario
import org.ergon.controlplane.evaluation.application.CapabilityScenarioStatus
import org.ergon.controlplane.evaluation.application.CapabilityScenarioTrace
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.ScenarioInvocationRunner
import org.ergon.controlplane.evaluation.application.simulationId
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionRepository
import org.ergon.controlplane.resolution.application.CapabilityConnectorGateway
import org.ergon.controlplane.resolution.application.CapabilityInvocationAttemptIdentityGenerator
import org.ergon.controlplane.resolution.application.CapabilityInvocationAttemptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.controlplane.resolution.application.CapabilityInvocationCompletion
import org.ergon.controlplane.resolution.application.CapabilityInvocationExecution
import org.ergon.controlplane.resolution.application.CapabilityInvocationPreparation
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationService
import org.ergon.controlplane.resolution.application.CapabilityInvocationTicker
import org.ergon.controlplane.resolution.application.StoredCapabilityAuthorizationConsumption
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionOutcomeProofBasis
import org.ergon.resolution.domain.ResolutionOutcomeProofEvaluator
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.List.copyOf
import java.util.UUID

/** Fresh isolated state per replay. This adapter never accepts or locates a live connector implementation. */
@Component
class ScriptedCapabilityInvocation : ScenarioInvocationRunner {
    override fun execute(
        input: CapabilityScenario,
        consumption: CapabilityAuthorizationConsumption,
    ): CapabilityScenarioTrace = InvocationSandbox(input, consumption).execute()
}

private class InvocationSandbox(
    private val input: CapabilityScenario,
    private val consumption: CapabilityAuthorizationConsumption,
) {
    private val digest = input.pinned.fixture.semanticDigest()
    private val tenant = TenantId(input.pinned.fixture.tenantId)
    private var inTransaction = false
    private var elapsed = 0L
    private var receipt: StoredCapabilityInvocationReceipt? = null
    private val keys = mutableListOf<UUID>()
    private val prepared = mutableListOf<CapabilityInvocationPreparation>()
    private val completions = mutableListOf<CapabilityInvocationCompletion>()
    private val clock =
        object : Clock() {
            override fun instant(): Instant = consumption.consumedAt.plusNanos(elapsed)

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock {
                require(zone == ZoneOffset.UTC)
                return this
            }
        }
    private val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T {
                check(!inTransaction)
                inTransaction = true
                return try {
                    block()
                } finally {
                    inTransaction = false
                }
            }
        }
    private val consumptions =
        object : CapabilityAuthorizationConsumptionRepository {
            override fun find(
                tenantId: TenantId,
                consumptionId: CapabilityAuthorizationConsumptionId,
            ): StoredCapabilityAuthorizationConsumption? {
                require(tenantId == tenant && consumptionId == consumption.id)
                return StoredCapabilityAuthorizationConsumption(consumption, consumption.consumedAt)
            }

            override fun findIdByGrant(
                tenantId: TenantId,
                grantId: CapabilityAuthorizationGrantId,
            ): CapabilityAuthorizationConsumptionId? {
                require(tenantId == tenant && grantId == consumption.authorizationGrantId)
                return consumption.id
            }

            override fun create(
                tenantId: TenantId,
                consumption: CapabilityAuthorizationConsumption,
            ): StoredCapabilityAuthorizationConsumption = error("simulation invocation cannot consume another grant")
        }
    private val receipts =
        object : CapabilityInvocationReceiptRepository {
            override fun find(
                tenantId: TenantId,
                consumptionId: CapabilityAuthorizationConsumptionId,
            ): StoredCapabilityInvocationReceipt? {
                require(tenantId == tenant && consumptionId == consumption.id)
                return receipt
            }

            override fun findByRun(
                tenantId: TenantId,
                runId: ResolutionRunId,
            ): StoredCapabilityInvocationReceipt? {
                require(tenantId == tenant && runId == consumption.runId)
                return receipt
            }

            override fun createOrFind(
                tenantId: TenantId,
                receipt: CapabilityInvocationReceipt,
            ): CapabilityInvocationExecution {
                check(inTransaction)
                require(tenantId == tenant && receipt.authorizationConsumptionId == consumption.id)
                val existing = this@InvocationSandbox.receipt
                val stored =
                    existing ?: StoredCapabilityInvocationReceipt(receipt, clock.instant())
                        .also { this@InvocationSandbox.receipt = it }
                return CapabilityInvocationExecution(stored, existing == null)
            }
        }
    private val audit =
        object : CapabilityInvocationAttemptRepository {
            override fun prepare(
                tenant: TenantId,
                preparation: CapabilityInvocationPreparation,
            ) {
                check(inTransaction && tenant == this@InvocationSandbox.tenant)
                require(preparation.consumptionId == consumption.id)
                require(prepared.none { it.id == preparation.id })
                prepared.add(preparation)
            }

            override fun complete(
                tenant: TenantId,
                attemptId: UUID,
                completion: CapabilityInvocationCompletion,
            ) {
                check(inTransaction && tenant == this@InvocationSandbox.tenant)
                check(prepared[completions.size].id == attemptId)
                completions.add(completion)
            }
        }
    private val gateway =
        CapabilityConnectorGateway { command ->
            check(!inTransaction && prepared.size == keys.size + 1)
            require(command.tenantId == tenant && command.consumption == consumption)
            require(command.idempotencyKey == consumption.id.value)
            val call = input.calls.getOrNull(keys.size) ?: throw InvalidSimulationFixture()
            keys.add(command.idempotencyKey)
            elapsed += call.elapsedNanoseconds
            if (call.outcome == CapabilityInvocationCallOutcome.UNKNOWN) throw ScriptedUncertainty()
            CapabilityInvocationResult(
                if (call.outcome ==
                    CapabilityInvocationCallOutcome.SUCCEEDED
                ) {
                    CapabilityInvocationOutcome.SUCCEEDED
                } else {
                    CapabilityInvocationOutcome.FAILED
                },
                ProviderOperationReference.of("simulation:${consumption.id.value}"),
            )
        }

    fun execute(): CapabilityScenarioTrace {
        val service =
            CapabilityInvocationService(
                consumptions,
                receipts,
                gateway,
                transactions,
                clock,
                CapabilityInvocationTicker { elapsed },
                audit,
                CapabilityInvocationAttemptIdentityGenerator { simulationId(digest, "attempt-${prepared.size}") },
            )
        repeat(input.invocationCount) {
            try {
                service.invoke(tenant.value, consumption.id.value)
            } catch (_: ScriptedUncertainty) {
                // A later invocation is an explicit scenario action, never an automatic retry policy.
            }
        }
        if (keys.size != input.calls.size) throw InvalidSimulationFixture()
        val terminal = receipt?.receipt
        val assessment =
            terminal?.takeIf { it.outcome == CapabilityInvocationOutcome.SUCCEEDED }?.let {
                ResolutionOutcomeProofEvaluator.evaluate(
                    ResolutionOutcomeProofBasis(
                        input.pinned.contract.outcomeProof,
                        input.pinned.fixture.casePrefix.streamVersion,
                        it.completedAt,
                        input.proofCaseVersion,
                        input.proof,
                    ),
                )
            }
        val status =
            when {
                terminal == null -> CapabilityScenarioStatus.INVOCATION_UNKNOWN
                terminal.outcome == CapabilityInvocationOutcome.FAILED -> CapabilityScenarioStatus.ACTION_FAILED
                assessment is ResolutionOutcomeProofAssessment.Accepted -> CapabilityScenarioStatus.PROOF_ACCEPTED
                else -> CapabilityScenarioStatus.PROOF_PENDING
            }
        return CapabilityScenarioTrace(digest, status, copyOf(keys), copyOf(completions), terminal, assessment)
    }
}

private class ScriptedUncertainty : RuntimeException()
