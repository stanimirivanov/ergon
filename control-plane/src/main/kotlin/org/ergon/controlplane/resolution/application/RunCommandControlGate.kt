package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Final transactional browser admission fence. Call after all potentially blocking writes, before commit,
 * while still holding the run lock. Provisional writes roll back when control expired during any lock wait.
 * The owning use case must already enforce its current supervision epoch, state and business prerequisites.
 * This gate neither wraps provider I/O nor applies to trusted runtime progression.
 */
class RunCommandControlGate(
    private val controls: RunControlRepository,
    private val authorities: HumanAuthorityRepository,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Fences a new effect and records its original lease. A disabled rollout accepts only legacy, unfenced intent.
     * @throws RunControlRequiredException when enabled but no fence was supplied.
     * @throws RunControlConflictException when the fence/session/epoch expired or changed.
     * @throws RunControlDisabledException for supplied control while protection is deliberately disabled.
     * @throws AssignedRunNotFoundException when resolver authority expired during transaction work.
     */
    fun admit(
        key: RunControlCommandKey,
        actorId: UUID,
        epoch: RunSupervisionRevision,
        control: RunCommandControl?,
    ) {
        val context = requiredContext(control) ?: return
        val tenant = TenantId(key.tenantId)
        val lease = controls.current(tenant, ResolutionRunId(key.runId)) ?: throw RunControlConflictException()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        requireResolver(tenant, actorId, now)
        val sameOwner = lease.command.actorId == actorId && lease.holderId == context.holderId
        val sameFence = lease.command.supervisionRevision == epoch && lease.fence == context.fence
        if (!sameOwner || !sameFence || !lease.activeAt(now)) {
            throw RunControlConflictException()
        }
        controls.recordAdmission(RunControlAdmission(key, lease, now))
    }

    private fun requiredContext(control: RunCommandControl?): RunCommandControl? {
        if (enabled) return control ?: throw RunControlRequiredException()
        if (control != null) throw RunControlDisabledException()
        return null
    }

    private fun requireResolver(
        tenant: TenantId,
        actorId: UUID,
        at: Instant,
    ) {
        if (authorities.findCurrent(tenant, HumanActorId(actorId), ApprovalAuthority.RESOLVER, null, at) == null) {
            throw AssignedRunNotFoundException()
        }
    }

    /**
     * Checks original wire intent only, after the owning use case has verified original actor and current resolver.
     * A different session may acknowledge the original fence; no live lease, renewal or takeover occurs.
     * Pre-rollout receipts have no fence and must still replay without one, even after enforcement is enabled.
     */
    fun replay(
        key: RunControlCommandKey,
        control: RunCommandControl?,
    ) {
        if (controls.admission(key)?.control?.fence != control?.fence) throw RunControlConflictException()
    }
}
