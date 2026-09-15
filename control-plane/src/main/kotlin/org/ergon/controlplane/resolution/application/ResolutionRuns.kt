package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.StepPolicyDecision
import java.time.Instant
import java.util.UUID

/** Immutable run start paired with the database instant at which it became durable. */
data class StoredResolutionRunStart(
    val run: ResolutionRunStart,
    val recordedAt: Instant,
)

/** Durable, tenant-scoped storage for immutable run starts and their initial state projections. */
interface ResolutionRunRepository {
    /**
     * Stores [run] only while its case remains at [ResolutionRunStart.caseStreamVersion].
     *
     * Implementations must lock the case version check and insert both the start
     * and its version-zero current-state projection in the caller's transaction.
     * This prevents evidence from changing between planning and the durable snapshot.
     *
     * @throws ConcurrentCaseModificationException when the case has advanced.
     * @throws ResolutionRunAlreadyExistsException when the case already has a run.
     */
    fun create(
        tenantId: TenantId,
        run: ResolutionRunStart,
    ): StoredResolutionRunStart

    /**
     * Stores [run] as the sole direct successor of its failed predecessor.
     *
     * Implementations must lock and compare the case version exactly as for
     * [create]. The caller owns the predecessor state lock and commits the
     * corresponding retry event in the same transaction.
     *
     * @throws ConcurrentCaseModificationException when the case has advanced.
     */
    fun createRetry(
        tenantId: TenantId,
        run: ResolutionRunStart,
    ): StoredResolutionRunStart

    /** @return the tenant-scoped run start, or `null` when it is absent. */
    fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunStart?
}

/** Supplies unpredictable resolution-run identities without coupling the use case to UUID generation. */
fun interface ResolutionRunIdentityGenerator {
    /** @return a fresh identity suitable for a durable run. */
    fun next(): ResolutionRunId
}

/** Signals that readiness or applicability does not permit starting a run. */
class ResolutionRunNotReadyException(
    val readiness: String,
) : RuntimeException("resolution run cannot start while readiness is $readiness")

/** Signals that policy denied the first contract step. */
class ResolutionRunPolicyDeniedException(
    val reason: String,
) : RuntimeException("resolution run cannot start because policy denied the step: $reason")

/** Signals that the current slice's one-run-per-case invariant was violated. */
class ResolutionRunAlreadyExistsException(
    caseId: UUID,
) : RuntimeException("case $caseId already has a resolution run")

/** Signals tenant-scoped run absence without revealing another tenant's data. */
class ResolutionRunNotFoundException(
    runId: UUID,
) : RuntimeException("resolution run $runId was not found")

/** Creates and retrieves immutable run-start snapshots from deterministic plans. */
class ResolutionRunService(
    private val planningService: ResolutionPlanner,
    private val repository: ResolutionRunRepository,
    private val identities: ResolutionRunIdentityGenerator,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Re-evaluates the plan and durably pins its first allowed step.
     *
     * [expectedCaseVersion] is checked before persistence and again under the
     * repository lock. A successful result records requirements only; it does
     * not satisfy an approval, establish actor authority, or invoke a capability.
     *
     * @throws ResolutionRunNotReadyException when evidence is incomplete or the contract does not apply.
     * @throws ResolutionRunPolicyDeniedException when policy denies the first step.
     * @throws ConcurrentCaseModificationException when [expectedCaseVersion] is stale.
     * @throws ResolutionRunAlreadyExistsException when the case already has a run.
     */
    fun start(
        tenantId: UUID,
        caseId: UUID,
        expectedCaseVersion: Long,
    ): StoredResolutionRunStart {
        require(expectedCaseVersion > 0) { "If-Match version must be positive" }
        return transactionRunner.required {
            val plan = planningService.plan(tenantId, caseId)
            val runPlan = plan.toRunPlan(expectedCaseVersion)
            val run =
                ResolutionRunStart.create(
                    id = identities.next(),
                    caseId = CaseId(caseId),
                    plan = runPlan,
                )
            repository.create(TenantId(tenantId), run)
        }
    }

    /**
     * Returns one immutable run start within [tenantId].
     *
     * @throws ResolutionRunNotFoundException when [runId] is absent from that tenant boundary.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
    ): StoredResolutionRunStart =
        repository.find(TenantId(tenantId), ResolutionRunId(runId))
            ?: throw ResolutionRunNotFoundException(runId)
}

private fun CaseResolutionPlan.requireVersion(expectedVersion: Long) {
    if (readiness.caseStreamVersion != expectedVersion) {
        throw ConcurrentCaseModificationException(expectedVersion, readiness.caseStreamVersion)
    }
}

private fun CaseResolutionPlan.requireReady(): CaseReadiness.Evaluated {
    val evaluated = readiness as? CaseReadiness.Evaluated
    if (evaluated == null || evaluated.readiness !is ResolutionReadiness.Ready) {
        throw ResolutionRunNotReadyException(readiness.statusName())
    }
    return evaluated
}

private fun CaseResolutionPlan.requireNextStep(): PlannedResolutionStep =
    checkNotNull(nextStep) { "ready resolution plan must contain its first step" }

private fun PlannedResolutionStep.requireAllowed(): StepPolicyDecision.Requirements =
    when (val decision = policyDecision) {
        is StepPolicyDecision.Denied -> throw ResolutionRunPolicyDeniedException(decision.reason.name)
        is StepPolicyDecision.Requirements -> decision
    }

/**
 * Converts current readiness and policy output into immutable inputs at [expectedVersion].
 *
 * @throws ConcurrentCaseModificationException when the plan describes another case version.
 * @throws ResolutionRunNotReadyException when evidence is incomplete or inapplicable.
 * @throws ResolutionRunPolicyDeniedException when policy denies the first step.
 */
internal fun CaseResolutionPlan.toRunPlan(expectedVersion: Long): ResolutionRunPlan {
    requireVersion(expectedVersion)
    val evaluated = requireReady()
    val planned = requireNextStep()
    return ResolutionRunPlan(
        caseStreamVersion = expectedVersion,
        contract = ResolutionContractIdentity(evaluated.contract.key, evaluated.contract.revision),
        policyRevision = policyRevision,
        stepId = planned.step.id,
        capability = planned.step.capability,
        decision = planned.requireAllowed(),
    )
}

private fun CaseReadiness.statusName(): String =
    when (this) {
        is CaseReadiness.WaitingForContract -> {
            "WAITING_FOR_CONTRACT"
        }

        is CaseReadiness.Evaluated -> {
            when (readiness) {
                is ResolutionReadiness.MissingEvidence -> "WAITING_FOR_EVIDENCE"
                is ResolutionReadiness.NotApplicable -> "NOT_APPLICABLE"
                is ResolutionReadiness.Ready -> "READY"
            }
        }
    }
