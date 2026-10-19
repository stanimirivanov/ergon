package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunSupervisorHandoverServiceTest {
    private val assignments = Mockito.mock(RunSupervisorAssignmentRepository::class.java)
    private val handovers = Mockito.mock(RunSupervisorHandoverRepository::class.java)
    private val candidates = Mockito.mock(RunSupervisorCandidateReader::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val states = Mockito.mock(ResolutionRunTransitionRepository::class.java)
    private val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T = block()
        }

    @Test
    fun `disabled rollout cannot acquire locks or disclose recipient identities`() {
        assertThatThrownBy { service(false).handover(command()) }
            .isInstanceOf(RunSupervisorHandoverDisabledException::class.java)
        assertThatThrownBy { service(false).listCandidates(TENANT.value, RUN.value, ACTOR.value, REVISION, 25, null) }
            .isInstanceOf(RunSupervisorHandoverDisabledException::class.java)
        Mockito.verifyNoInteractions(assignments, handovers, candidates, authorities, states)
    }

    @Test
    fun `missing run or outgoing authority cannot inspect past commands or recipients`() {
        assertThatThrownBy { service().handover(command()) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(handovers, candidates, authorities, states)
        Mockito.`when`(assignments.lockRun(TENANT, RUN)).thenReturn(true)
        assertThatThrownBy { service().handover(command()) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(handovers, candidates, states)
    }

    @Test
    fun `exact historical replay does not look up or change current supervision`() {
        allowCaller()
        val receipt = StoredRunSupervisorHandover(fact(), NOW)
        Mockito.`when`(handovers.findByCommandId(TENANT, COMMAND_ID)).thenReturn(receipt)
        val result = service().handover(command())
        assertThat(result).isEqualTo(RunSupervisorHandoverRecording(receipt, false))
        Mockito.verify(assignments, Mockito.never()).findAssigned(TENANT, RUN, ACTOR, NOW)
        Mockito.verifyNoInteractions(states, candidates)
        Mockito.verify(handovers, Mockito.never()).record(TENANT, receipt.handover)
    }

    @Test
    fun `foreign replay is neutral absence and changed own intent is a conflict`() {
        allowCaller()
        Mockito
            .`when`(handovers.findByCommandId(TENANT, COMMAND_ID))
            .thenReturn(
                StoredRunSupervisorHandover(fact().copy(previousActorId = HumanActorId(UUID.randomUUID())), NOW),
            )
        assertThatThrownBy { service().handover(command()) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito
            .`when`(handovers.findByCommandId(TENANT, COMMAND_ID))
            .thenReturn(StoredRunSupervisorHandover(fact(), NOW))
        assertThatThrownBy { service().handover(command().copy(supervisorActorId = UUID.randomUUID())) }
            .isInstanceOf(RunSupervisorHandoverConflictException::class.java)
        Mockito.verifyNoInteractions(states, candidates)
    }

    @Test
    fun `stale supervision epoch is rejected before locking state or resolving recipient`() {
        allowCaller()
        Mockito.`when`(assignments.findAssigned(TENANT, RUN, ACTOR, NOW)).thenReturn(current(RunSupervisionRevision(2)))
        assertThatThrownBy { service().handover(command()) }
            .isInstanceOf(RunSupervisorHandoverConflictException::class.java)
        Mockito.verifyNoInteractions(states, candidates)
        Mockito
            .verify(authorities, Mockito.never())
            .findCurrent(TENANT, RECIPIENT, ApprovalAuthority.RESOLVER, null, NOW)
    }

    @Test
    fun `terminal state and ineligible recipient never append an event`() {
        allowCaller()
        Mockito.`when`(assignments.findAssigned(TENANT, RUN, ACTOR, NOW)).thenReturn(current())
        Mockito
            .`when`(states.lockState(TENANT, RUN))
            .thenReturn(ResolutionRunStateSnapshot(RUN, ResolutionRunState.VERIFIED_RESOLVED, 2, NOW))
        assertThatThrownBy { service().handover(command()) }
            .isInstanceOf(RunSupervisorHandoverUnavailableException::class.java)
        Mockito
            .`when`(states.lockState(TENANT, RUN))
            .thenReturn(ResolutionRunStateSnapshot(RUN, ResolutionRunState.WAITING_FOR_APPROVAL, 0, NOW))
        assertThatThrownBy { service().handover(command()) }.isInstanceOf(RunSupervisorNotEligibleException::class.java)
        assertThatThrownBy { service().handover(command().copy(supervisorActorId = ACTOR.value)) }
            .isInstanceOf(RunSupervisorNotEligibleException::class.java)
        assertThat(Mockito.mockingDetails(handovers).invocations.filter { it.method.name == "record" }).isEmpty()
    }

    @Test
    fun `an exhausted browser-safe epoch refuses transfer without an audit append`() {
        allowCaller()
        val exhausted = RunSupervisionRevision(MAX_RUN_SUPERVISION_REVISION)
        Mockito.`when`(assignments.findAssigned(TENANT, RUN, ACTOR, NOW)).thenReturn(current(exhausted))
        Mockito
            .`when`(states.lockState(TENANT, RUN))
            .thenReturn(ResolutionRunStateSnapshot(RUN, ResolutionRunState.WAITING_FOR_APPROVAL, 0, NOW))
        assertThatThrownBy { service().handover(command().copy(expectedRevision = exhausted)) }
            .isInstanceOf(RunSupervisorHandoverUnavailableException::class.java)
        assertThat(Mockito.mockingDetails(handovers).invocations.filter { it.method.name == "record" }).isEmpty()
        assertThatThrownBy { exhausted.next() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `eligible command retains both current authority attestations and original assignment`() {
        allowCaller()
        Mockito.`when`(assignments.findAssigned(TENANT, RUN, ACTOR, NOW)).thenReturn(current())
        Mockito
            .`when`(states.lockState(TENANT, RUN))
            .thenReturn(ResolutionRunStateSnapshot(RUN, ResolutionRunState.WAITING_FOR_APPROVAL, 0, NOW))
        Mockito
            .`when`(authorities.findCurrent(TENANT, RECIPIENT, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(evidence(RECIPIENT, INCOMING_EVIDENCE))
        Mockito.`when`(handovers.record(TENANT, fact())).thenReturn(StoredRunSupervisorHandover(fact(), NOW))
        assertThat(service().handover(command()).created).isTrue()
        Mockito.verify(handovers).record(TENANT, fact())
        val order = Mockito.inOrder(assignments, handovers, states)
        order.verify(assignments).lockRun(TENANT, RUN)
        order.verify(handovers).lockCommand(TENANT, COMMAND_ID)
        order.verify(handovers).findByCommandId(TENANT, COMMAND_ID)
        order.verify(assignments).findAssigned(TENANT, RUN, ACTOR, NOW)
        order.verify(states).lockState(TENANT, RUN)
        order.verify(handovers).record(TENANT, fact())
    }

    @Test
    fun `candidate discovery gates before lookup and uses bounded private UUID traversal`() {
        assertThatThrownBy { service().listCandidates(TENANT.value, RUN.value, ACTOR.value, REVISION, 1, null) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(candidates, states)
        Mockito.`when`(assignments.findAssigned(TENANT, RUN, ACTOR, NOW)).thenReturn(current())
        Mockito
            .`when`(states.findState(TENANT, RUN))
            .thenReturn(ResolutionRunStateSnapshot(RUN, ResolutionRunState.WAITING_FOR_APPROVAL, 0, NOW))
        Mockito
            .`when`(candidates.listCandidates(TENANT, ACTOR, NOW, 2, null))
            .thenReturn(listOf(RECIPIENT, HumanActorId(UUID.randomUUID())))
        val page = service().listCandidates(TENANT.value, RUN.value, ACTOR.value, REVISION, 1, null)
        assertThat(page.actorIds).containsExactly(RECIPIENT)
        assertThat(page.nextActorId).isEqualTo(RECIPIENT.value)
        assertThat(page.supervisionRevision).isEqualTo(REVISION)
    }

    private fun allowCaller() {
        Mockito.`when`(assignments.lockRun(TENANT, RUN)).thenReturn(true)
        Mockito
            .`when`(authorities.findCurrent(TENANT, ACTOR, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(evidence(ACTOR, OUTGOING_EVIDENCE))
    }

    private fun current(revision: RunSupervisionRevision = REVISION) =
        CurrentRunSupervision(
            StoredRunSupervisorAssignment(
                RunSupervisorAssignment(
                    ASSIGNMENT_ID,
                    RUN,
                    ACTOR,
                    OUTGOING_EVIDENCE,
                    UUID.randomUUID(),
                    "dispatcher",
                    NOW,
                ),
                NOW,
            ),
            ACTOR,
            revision,
            NOW,
        )

    private fun command() =
        HandoverRunSupervisorCommand(
            TENANT.value,
            RUN.value,
            ACTOR.value,
            RECIPIENT.value,
            COMMAND_ID,
            REVISION,
        )

    private fun fact() =
        RunSupervisorHandover(
            RUN,
            ASSIGNMENT_ID,
            COMMAND_ID,
            REVISION.next(),
            ACTOR,
            RECIPIENT,
            OUTGOING_EVIDENCE,
            INCOMING_EVIDENCE,
            NOW,
        )

    private fun evidence(
        actor: HumanActorId,
        id: ApprovalAuthorityEvidenceId,
    ) = StoredApprovalAuthorityEvidence(
        ApprovalAuthorityEvidence(
            id,
            actor,
            ApprovalAuthority.RESOLVER,
            null,
            ApprovalAuthorityEvidenceSource.create("workforce-sso", "resolvers"),
            NOW.minusSeconds(60),
            NOW.plusSeconds(60),
        ),
        NOW,
    )

    private fun service(enabled: Boolean = true) =
        RunSupervisorHandoverService(
            assignments,
            handovers,
            candidates,
            authorities,
            states,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            enabled,
            controls = Mockito.mock(RunCommandControlGate::class.java),
        )

    companion object {
        private val TENANT = TenantId(UUID.randomUUID())
        private val RUN = ResolutionRunId(UUID.randomUUID())
        private val ACTOR = HumanActorId(UUID.randomUUID())
        private val RECIPIENT = HumanActorId(UUID.randomUUID())
        private val ASSIGNMENT_ID = UUID.randomUUID()
        private val COMMAND_ID = UUID.randomUUID()
        private val OUTGOING_EVIDENCE = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        private val INCOMING_EVIDENCE = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        private val REVISION = RunSupervisionRevision(0)
        private val NOW = Instant.parse("2026-10-14T07:00:00Z")
    }
}
