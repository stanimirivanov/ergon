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
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunProofFinalizationServiceTest {
    private val assignments = Mockito.mock(RunSupervisorAssignmentRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val runs = Mockito.mock(ResolutionRunRepository::class.java)
    private val states = Mockito.mock(ResolutionRunTransitionRepository::class.java)
    private val assessments = Mockito.mock(ResolutionOutcomeProofAssessmentService::class.java)
    private val completion = Mockito.mock(ResolutionOutcomeProofCompletion::class.java)
    private val commands = Mockito.mock(RunProofCommandRepository::class.java)
    private val command =
        FinalizeRunProofCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            1,
            6,
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
    private val tenant = TenantId(command.tenantId)
    private val run = ResolutionRunId(command.runId)
    private val actor = HumanActorId(command.actorId)
    private val now = Instant.parse("2026-10-15T08:00:00Z")

    @Test
    fun `default-off command touches no private or mutation boundary`() {
        assertThatThrownBy { service(enabled = false).finalizeProof(command) }
            .isInstanceOf(RunProofFinalizationDisabledException::class.java)
        Mockito.verifyNoInteractions(assignments, authorities, runs, states, assessments, completion, commands)
    }

    @Test
    fun `missing resolver authority denies before receipt or evidence lookup`() {
        Mockito.`when`(assignments.lockRun(tenant, run)).thenReturn(true)
        assertThatThrownBy { service().finalizeProof(command) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(commands, runs, states, assessments, completion)
    }

    @Test
    fun `exact replay needs current resolver but not current supervision or another assessment`() {
        prepareReplay()
        val stored = StoredRunProofCommand(command, now, now)
        Mockito.`when`(commands.findByCommandId(tenant, command.commandId)).thenReturn(stored)
        assertThat(service().finalizeProof(command)).isEqualTo(RunProofFinalizationRecording(stored, false))
        Mockito.verify(assignments, Mockito.never()).findAssigned(tenant, run, actor, now)
        Mockito.verifyNoInteractions(runs, states, assessments, completion)
    }

    @Test
    fun `authority lost while waiting for command lock denies even historical replay`() {
        prepareReplay()
        Mockito
            .`when`(commands.findByCommandId(tenant, command.commandId))
            .thenReturn(StoredRunProofCommand(command, now, now))
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, now))
            .thenReturn(evidence(), null)
        assertThatThrownBy { service().finalizeProof(command) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(runs, states, assessments, completion)
    }

    @Test
    fun `foreign replay is neutral while changed originating intent conflicts`() {
        prepareReplay()
        Mockito.`when`(commands.findByCommandId(tenant, command.commandId)).thenReturn(
            StoredRunProofCommand(command.copy(actorId = UUID.randomUUID()), now, now),
        )
        assertThatThrownBy { service().finalizeProof(command) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.`when`(commands.findByCommandId(tenant, command.commandId)).thenReturn(
            StoredRunProofCommand(command.copy(expectedCaseVersion = 8), now, now),
        )
        assertThatThrownBy { service().finalizeProof(command) }
            .isInstanceOf(RunProofFinalizationConflictException::class.java)
        Mockito.verifyNoInteractions(runs, states, assessments, completion)
    }

    private fun prepareReplay() {
        Mockito.`when`(assignments.lockRun(tenant, run)).thenReturn(true)
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, now))
            .thenReturn(evidence())
    }

    private fun evidence() =
        StoredApprovalAuthorityEvidence(
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                actor,
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("workforce-sso", "synthetic-resolvers"),
                now.minusSeconds(60),
                now.plusSeconds(3600),
            ),
            now,
        )

    private fun service(enabled: Boolean = true) =
        RunProofFinalizationService(
            assignments,
            authorities,
            runs,
            states,
            assessments,
            completion,
            commands,
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            },
            Clock.fixed(now, ZoneOffset.UTC),
            enabled,
        )
}
