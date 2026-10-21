package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Immutable declarations, not readiness, effective policy, performed steps or accepted proof. */
data class RunPinnedContract(
    val runId: ResolutionRunId,
    val supervisionRevision: RunSupervisionRevision,
    val observedAt: Instant,
    val pinnedStepId: ResolutionStepId,
    val revision: StoredResolutionContractRevision,
)

/** Reads only the exact revision pinned by a currently supervised run, including terminal attempts. */
class RunPinnedContractService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val runs: ResolutionRunRepository,
    private val contracts: ResolutionContractRevisionRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Checks current tenant-wide resolver evidence and exact supervision before
     * reading private run/contract data. The transaction joins local immutable
     * reads, not a control lease or a continuous/atomic authority guarantee.
     *
     * @throws AssignedRunNotFoundException for missing/foreign/unassigned/former
     *   supervision or expired resolver authority.
     * @throws IllegalStateException when durable run, revision or pinned-step
     *   attribution is inconsistent. Storage failures propagate, never become absence.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunPinnedContract =
        transactions.required {
            val tenant = TenantId(tenantId)
            val runIdentity = ResolutionRunId(runId)
            val actor = HumanActorId(actorId)
            val observedAt = clock.instant()
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, observedAt) == null) {
                throw AssignedRunNotFoundException()
            }
            val supervision =
                assignments.findAssigned(tenant, runIdentity, actor, observedAt) ?: throw AssignedRunNotFoundException()
            val run = checkNotNull(runs.find(tenant, runIdentity)) { "supervised run is missing" }.run
            check(run.id == runIdentity) { "supervised run identity is inconsistent" }
            val stored =
                checkNotNull(contracts.find(tenant, run.contract.key, run.contract.revision)) {
                    "pinned contract revision is missing"
                }
            val contract = stored.contract
            check(contract.key == run.contract.key && contract.revision == run.contract.revision) {
                "pinned contract identity is inconsistent"
            }
            check(contract.steps.singleOrNull { it.id == run.stepId }?.capability == run.capability) {
                "pinned capability is inconsistent"
            }
            RunPinnedContract(runIdentity, supervision.revision, observedAt, run.stepId, stored)
        }
}
