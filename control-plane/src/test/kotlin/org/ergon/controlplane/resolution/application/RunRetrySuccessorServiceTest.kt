package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunRetrySnapshot
import org.ergon.resolution.domain.ResolutionRunRetryStarted
import org.ergon.resolution.domain.ResolutionRunState
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunRetrySuccessorServiceTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val source = ResolutionRunId(UUID.randomUUID())
    private val target = ResolutionRunId(UUID.randomUUID())
    private val actor = HumanActorId(UUID.randomUUID())
    private val at = Instant.parse("2026-10-20T07:00:00Z")
    private val assignments = Mockito.mock(RunSupervisorAssignmentRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val retries = Mockito.mock(ResolutionRunRetryRepository::class.java)
    private val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T = block()
        }
    private val service =
        RunRetrySuccessorService(
            assignments,
            authorities,
            retries,
            transactions,
            Clock.fixed(at, ZoneOffset.UTC),
        )

    @Test
    fun `missing current resolver evidence rejects before any assignment or history lookup`() {
        assertThatThrownBy { read() }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at)
        Mockito.verifyNoInteractions(assignments, retries)
    }

    @Test
    fun `inaccessible predecessor rejects before retry lookup`() {
        authorizeResolver()
        assertThatThrownBy { read() }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verify(assignments).findAssigned(tenant, source, actor, at)
        Mockito.verifyNoInteractions(retries)
    }

    @Test
    fun `no retry returns source observation without looking up a target`() {
        authorizeSource()
        assertThat(read()).isEqualTo(RunRetrySuccessor(at, RunSupervisionRevision(3), null))
        Mockito.verify(assignments).findAssigned(tenant, source, actor, at)
        Mockito.verifyNoMoreInteractions(assignments)
        Mockito.verify(retries).find(tenant, source)
        Mockito.verifyNoMoreInteractions(retries)
    }

    @Test
    fun `hidden successor is indistinguishable from absent retry`() {
        authorizeSource()
        val absent = read()
        Mockito.`when`(retries.find(tenant, source)).thenReturn(recordedRetry())
        assertThat(read()).isEqualTo(absent)
        Mockito.verify(assignments).findAssigned(tenant, target, actor, at)
    }

    @Test
    fun `independent gates expose only the target identity and its own observed epoch`() {
        authorizeSource()
        Mockito.`when`(retries.find(tenant, source)).thenReturn(recordedRetry())
        Mockito.`when`(assignments.findAssigned(tenant, target, actor, at)).thenReturn(supervision(7))
        assertThat(read()).isEqualTo(
            RunRetrySuccessor(at, RunSupervisionRevision(3), VisibleRetrySuccessor(target, RunSupervisionRevision(7))),
        )
        val order = Mockito.inOrder(authorities, assignments, retries)
        order.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at)
        order.verify(assignments).findAssigned(tenant, source, actor, at)
        order.verify(retries).find(tenant, source)
        order.verify(assignments).findAssigned(tenant, target, actor, at)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun `history storage failure propagates instead of fabricating no retry`() {
        authorizeSource()
        val failure = IllegalStateException("private history failure")
        Mockito.`when`(retries.find(tenant, source)).thenThrow(failure)
        assertThatThrownBy { read() }.isSameAs(failure)
        Mockito.verify(assignments).findAssigned(tenant, source, actor, at)
        Mockito.verifyNoMoreInteractions(assignments)
    }

    @Test
    fun `target gate failure propagates instead of fabricating hidden target`() {
        authorizeSource()
        Mockito.`when`(retries.find(tenant, source)).thenReturn(recordedRetry())
        val failure = IllegalStateException("private target failure")
        Mockito.`when`(assignments.findAssigned(tenant, target, actor, at)).thenThrow(failure)
        assertThatThrownBy { read() }.isSameAs(failure)
    }

    private fun read(): RunRetrySuccessor = service.get(tenant.value, source.value, actor.value)

    private fun authorizeResolver() {
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at))
            .thenReturn(Mockito.mock(StoredApprovalAuthorityEvidence::class.java))
    }

    private fun authorizeSource() {
        authorizeResolver()
        Mockito.`when`(assignments.findAssigned(tenant, source, actor, at)).thenReturn(supervision(3))
    }

    private fun supervision(revision: Long): CurrentRunSupervision =
        CurrentRunSupervision(
            Mockito.mock(StoredRunSupervisorAssignment::class.java),
            actor,
            RunSupervisionRevision(revision),
            at,
        )

    private fun recordedRetry(): StoredResolutionRunRetry =
        StoredResolutionRunRetry(
            ResolutionRunRetryStarted.rehydrate(
                ResolutionRunRetrySnapshot(
                    ResolutionRunEventId(UUID.randomUUID()),
                    source,
                    target,
                    2,
                    ResolutionRunState.ACTION_FAILED,
                    ResolutionRunState.SUPERSEDED,
                    authorization = null,
                    eligibility = null,
                    occurredAt = at,
                ),
            ),
            at,
        )
}
