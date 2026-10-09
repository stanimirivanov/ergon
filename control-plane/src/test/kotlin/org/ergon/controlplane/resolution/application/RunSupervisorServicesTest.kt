package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
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
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunSupervisorServicesTest {
    private val assignments = Mockito.mock(RunSupervisorAssignmentRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val runs = Mockito.mock(ResolutionRunRepository::class.java)
    private val transitions = Mockito.mock(ResolutionRunTransitionRepository::class.java)
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val transactionRunner =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T = block()
        }

    @Test
    fun `exact machine replay returns the recorded assignment before rechecking current eligibility`() {
        val recorded = storedAssignment()
        Mockito.`when`(assignments.lockRun(TENANT_ID, RUN_ID)).thenReturn(true)
        Mockito.`when`(assignments.findByCommandId(TENANT_ID, COMMAND_ID)).thenReturn(recorded)

        val result = assignmentService().assign(command())

        assertThat(result).isEqualTo(RunSupervisorAssignmentRecording(recorded, created = false))
        Mockito.verifyNoInteractions(authorities)
        Mockito.verify(assignments).lockRun(TENANT_ID, RUN_ID)
        Mockito.verify(assignments).findByCommandId(TENANT_ID, COMMAND_ID)
        Mockito.verifyNoMoreInteractions(assignments)
    }

    @Test
    fun `a reused command cannot silently assign another actor`() {
        Mockito.`when`(assignments.lockRun(TENANT_ID, RUN_ID)).thenReturn(true)
        Mockito.`when`(assignments.findByCommandId(TENANT_ID, COMMAND_ID)).thenReturn(storedAssignment())

        assertThatThrownBy { assignmentService().assign(command().copy(supervisorActorId = OTHER_ACTOR_ID.value)) }
            .isInstanceOf(RunSupervisorAssignmentConflictException::class.java)
        Mockito.verifyNoInteractions(authorities)
    }

    @Test
    fun `first assignment refuses an actor without current resolver evidence`() {
        Mockito.`when`(assignments.lockRun(TENANT_ID, RUN_ID)).thenReturn(true)

        assertThatThrownBy { assignmentService().assign(command()) }
            .isInstanceOf(RunSupervisorNotEligibleException::class.java)
        Mockito.verify(authorities).findCurrent(TENANT_ID, ACTOR_ID, ApprovalAuthority.RESOLVER, null, NOW)
        Mockito.verify(assignments).lockRun(TENANT_ID, RUN_ID)
        Mockito.verify(assignments).findByCommandId(TENANT_ID, COMMAND_ID)
        Mockito.verify(assignments).findByRun(TENANT_ID, RUN_ID)
        Mockito.verifyNoMoreInteractions(assignments)
    }

    @Test
    fun `first assignment records the current resolver evidence and verified machine subject`() {
        Mockito.`when`(assignments.lockRun(TENANT_ID, RUN_ID)).thenReturn(true)
        Mockito
            .`when`(authorities.findCurrent(TENANT_ID, ACTOR_ID, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(storedEvidence())
        val expected =
            RunSupervisorAssignment(
                ASSIGNMENT_ID,
                RUN_ID,
                ACTOR_ID,
                EVIDENCE_ID,
                COMMAND_ID,
                "run-dispatcher",
                NOW,
            )
        Mockito
            .`when`(assignments.create(TENANT_ID, expected))
            .thenReturn(StoredRunSupervisorAssignment(expected, NOW))

        val result = assignmentService().assign(command())

        assertThat(result.created).isTrue()
        assertThat(result.stored.assignment.runId).isEqualTo(RUN_ID)
        assertThat(result.stored.assignment.supervisorActorId).isEqualTo(ACTOR_ID)
        assertThat(result.stored.assignment.authorityEvidenceId).isEqualTo(EVIDENCE_ID)
        assertThat(result.stored.assignment.assigningMachineSubject).isEqualTo("run-dispatcher")
    }

    @Test
    fun `unknown run is rejected before assignment and authority lookup`() {
        Mockito.`when`(assignments.lockRun(TENANT_ID, RUN_ID)).thenReturn(false)

        assertThatThrownBy { assignmentService().assign(command()) }
            .isInstanceOf(SupervisedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(authorities)
        Mockito.verify(assignments, Mockito.never()).findByCommandId(TENANT_ID, COMMAND_ID)
    }

    @Test
    fun `browser detail with lost resolver authority does not query assignments or run data`() {
        assertThatThrownBy { consoleService().get(TENANT_ID.value, RUN_ID.value, ACTOR_ID.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(assignments, runs, transitions)
    }

    @Test
    fun `browser detail for another assignee does not query protected run data`() {
        val evidence = storedEvidence()
        Mockito
            .`when`(authorities.findCurrent(TENANT_ID, ACTOR_ID, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(evidence)

        assertThatThrownBy { consoleService().get(TENANT_ID.value, RUN_ID.value, ACTOR_ID.value) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verify(assignments).findAssigned(TENANT_ID, RUN_ID, ACTOR_ID, NOW)
        Mockito.verifyNoInteractions(runs, transitions)
    }

    @Test
    fun `browser list hides all runs when current resolver authority is absent`() {
        val page = consoleService().list(TENANT_ID.value, ACTOR_ID.value, 30, null)

        assertThat(page.entries).isEmpty()
        assertThat(page.nextCursor).isNull()
        Mockito.verifyNoInteractions(assignments)
    }

    @Test
    fun `assigned discovery emits a cursor only when a lookahead row exists`() {
        val evidence = storedEvidence()
        Mockito
            .`when`(authorities.findCurrent(TENANT_ID, ACTOR_ID, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(evidence)
        val first = overview(ASSIGNMENT_ID, RUN_ID)
        val second = overview(UUID.randomUUID(), ResolutionRunId(UUID.randomUUID()))
        Mockito
            .`when`(assignments.listAssigned(TENANT_ID, ACTOR_ID, NOW, 2, null))
            .thenReturn(listOf(first, second))

        val page = consoleService().list(TENANT_ID.value, ACTOR_ID.value, 1, null)

        assertThat(page.entries).containsExactly(first)
        assertThat(page.nextCursor).isEqualTo(AssignedRunCursor(first.assignedAt, first.assignmentId))
    }

    private fun assignmentService() =
        RunSupervisorAssignmentService(
            assignments,
            authorities,
            RunSupervisorAssignmentIdentityGenerator { ASSIGNMENT_ID },
            transactionRunner,
            clock,
        )

    private fun consoleService() =
        AssignedRunConsoleService(
            assignments,
            authorities,
            runs,
            transitions,
            transactionRunner,
            clock,
        )

    private fun command() =
        AssignRunSupervisorCommand(
            TENANT_ID.value,
            RUN_ID.value,
            ACTOR_ID.value,
            COMMAND_ID,
            "run-dispatcher",
        )

    private fun storedAssignment() =
        StoredRunSupervisorAssignment(
            RunSupervisorAssignment(
                ASSIGNMENT_ID,
                RUN_ID,
                ACTOR_ID,
                ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                COMMAND_ID,
                "run-dispatcher",
                NOW,
            ),
            NOW,
        )

    private fun storedEvidence() =
        StoredApprovalAuthorityEvidence(
            ApprovalAuthorityEvidence(
                id = EVIDENCE_ID,
                actorId = ACTOR_ID,
                authority = ApprovalAuthority.RESOLVER,
                caseId = null,
                source = ApprovalAuthorityEvidenceSource.create("workforce-sso", "evidence-1"),
                attestedAt = NOW.minusSeconds(60),
                expiresAt = NOW.plusSeconds(3600),
            ),
            NOW,
        )

    private fun overview(
        assignmentId: UUID,
        runId: ResolutionRunId,
    ) = AssignedRunOverview(
        assignmentId,
        runId,
        CaseId(UUID.randomUUID()),
        ResolutionRunState.VERIFYING,
        1,
        NOW,
        NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-09T04:00:00Z")
        val TENANT_ID = TenantId(UUID.fromString("e3685f32-e822-41d0-9ef2-c226863b7789"))
        val RUN_ID = ResolutionRunId(UUID.fromString("8774d59f-dd1d-44a9-bca5-7620db291f92"))
        val ACTOR_ID = HumanActorId(UUID.fromString("f1793d52-cdc4-48cf-a4a0-ee3637763544"))
        val OTHER_ACTOR_ID = HumanActorId(UUID.fromString("0b2f8166-ce84-49a1-9fc9-3b58c9923d60"))
        val ASSIGNMENT_ID = UUID.fromString("26782195-e230-4af8-bf3b-1640057a2a7d")
        val COMMAND_ID = UUID.fromString("87e62fa4-75b2-41a8-ab31-a3d4d2081c8e")
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.fromString("3f3d993d-6f7a-4f0d-8c70-0df388223bdb"))
    }
}
