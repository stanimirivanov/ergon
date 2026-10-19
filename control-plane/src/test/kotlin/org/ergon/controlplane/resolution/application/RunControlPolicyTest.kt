package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunControlPolicyTest {
    private val repository = Mockito.mock(RunControlRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenant = UUID.randomUUID()
    private val run = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val holder = UUID.randomUUID()
    private val fence = RunControlFence(UUID.randomUUID(), 1)
    private val key = RunControlCommandKey(tenant, run, RunControlCommandKind.APPROVAL, UUID.randomUUID())
    private val command =
        RunControlCommand(
            tenant,
            run,
            actor,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            RunControlAction.ACQUIRE,
            null,
        )
    private val lease = StoredRunControl(command, 1, fence, holder, now, now.plusSeconds(120), now, now)

    @Test
    fun `fences and lifecycle snapshots cannot encode unsafe or contradictory values`() {
        for (revision in listOf(0, MAX_RUN_SUPERVISION_REVISION + 1)) {
            assertThatThrownBy { RunControlFence(UUID.randomUUID(), revision) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { command.copy(action = RunControlAction.RENEW) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { command.copy(expectedFence = fence) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { lease.copy(sequence = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { lease.copy(expiresAt = now.plusSeconds(121)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(lease.activeAt(now)).isTrue()
        assertThat(lease.activeAt(now.minusNanos(1))).isFalse()
        assertThat(lease.activeAt(lease.expiresAt)).isFalse()
        val released = lease.copy(command = command.copy(action = RunControlAction.RELEASE, expectedFence = fence))
        assertThat(released.activeAt(now))
            .isFalse()
    }

    @Test
    fun `disabled admission preserves only unfenced legacy behavior and never pretends a fence was enforced`() {
        val gate = RunCommandControlGate(repository, authorities, clock, false)
        gate.admit(key, actor, command.supervisionRevision, null)
        Mockito.verifyNoInteractions(repository, authorities)
        assertThatThrownBy { gate.admit(key, actor, command.supervisionRevision, RunCommandControl(fence, holder)) }
            .isInstanceOf(RunControlDisabledException::class.java)
    }

    @Test
    fun `historical replay requires original fence but not live control session or resolver lookups`() {
        val admission = RunControlAdmission(key, lease, now)
        Mockito.`when`(repository.admission(key)).thenReturn(admission)
        val gate = RunCommandControlGate(repository, authorities, Clock.fixed(lease.expiresAt, ZoneOffset.UTC), true)
        gate.replay(key, RunCommandControl(fence, UUID.randomUUID()))
        assertThatThrownBy { gate.replay(key, null) }.isInstanceOf(RunControlConflictException::class.java)
        assertThatThrownBy { gate.replay(key, RunCommandControl(fence.copy(revision = 2), holder)) }
            .isInstanceOf(RunControlConflictException::class.java)
        Mockito.verifyNoInteractions(authorities)
        Mockito.verify(repository, Mockito.never()).current(TenantId(tenant), ResolutionRunId(run))
        // Explicitly disabling protection does not erase the exact fence of already committed history.
        RunCommandControlGate(repository, authorities, clock, false).replay(key, RunCommandControl(fence, holder))
    }

    @Test
    fun `pre-rollout history remains replayable without inventing control after enforcement is enabled`() {
        val gate = RunCommandControlGate(repository, authorities, clock, true)
        gate.replay(key, null)
        assertThatThrownBy { gate.replay(key, RunCommandControl(fence, holder)) }
            .isInstanceOf(RunControlConflictException::class.java)
        assertThatThrownBy {
            gate.admit(
                key,
                actor,
                command.supervisionRevision,
                null,
            )
        }.isInstanceOf(RunControlRequiredException::class.java)
        Mockito.verifyNoInteractions(authorities)
    }
}
