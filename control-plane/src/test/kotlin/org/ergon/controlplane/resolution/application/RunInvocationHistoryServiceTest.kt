package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunInvocationHistoryServiceTest {
    private val tenant = TenantId(UUID(0, 1))
    private val run = ResolutionRunId(UUID(0, 2))
    private val actor = HumanActorId(UUID(0, 3))
    private val at = Instant.parse("2026-10-20T08:00:00Z")
    private val assignments = Mockito.mock(RunSupervisorAssignmentRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val history = Mockito.mock(RunInvocationHistoryReader::class.java)
    private var active = false
    private val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T {
                check(!active)
                active = true
                return try {
                    block()
                } finally {
                    active = false
                }
            }
        }
    private val service =
        RunInvocationHistoryService(
            assignments,
            authorities,
            history,
            transactions,
            Clock.fixed(at, ZoneOffset.UTC),
        )

    @Test
    fun `missing resolver evidence prevents assignment and history reads`() {
        assertThatThrownBy { read() }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at)
        Mockito.verifyNoInteractions(assignments, history)
    }

    @Test
    fun `missing exact current supervision prevents all history reads`() {
        authorizeResolver()
        assertThatThrownBy { read() }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verify(assignments).findAssigned(tenant, run, actor, at)
        Mockito.verifyNoInteractions(history)
    }

    @Test
    fun `current gates precede bounded history in an application transaction without command or lease authority`() {
        authorize()
        val records = RecordedInvocationAttempts(emptyList(), truncated = false)
        Mockito.`when`(history.read(tenant, run)).thenAnswer {
            check(active)
            records
        }
        assertThat(read()).isEqualTo(RunInvocationHistory(at, RunSupervisionRevision(4), records))
        val order = Mockito.inOrder(authorities, assignments, history)
        order.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at)
        order.verify(assignments).findAssigned(tenant, run, actor, at)
        order.verify(history).read(tenant, run)
        order.verifyNoMoreInteractions()
        assertThat(active).isFalse()
    }

    @Test
    fun `storage failure propagates rather than fabricating empty history`() {
        authorize()
        val failure = IllegalStateException("private synthetic history failure")
        Mockito.`when`(history.read(tenant, run)).thenThrow(failure)
        assertThatThrownBy { read() }.isSameAs(failure)
        assertThat(active).isFalse()
    }

    private fun read(): RunInvocationHistory = service.get(tenant.value, run.value, actor.value)

    private fun authorizeResolver() {
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at))
            .thenReturn(Mockito.mock(StoredApprovalAuthorityEvidence::class.java))
    }

    private fun authorize() {
        authorizeResolver()
        Mockito.`when`(assignments.findAssigned(tenant, run, actor, at)).thenReturn(
            CurrentRunSupervision(
                Mockito.mock(StoredRunSupervisorAssignment::class.java),
                actor,
                RunSupervisionRevision(4),
                at,
            ),
        )
    }
}
