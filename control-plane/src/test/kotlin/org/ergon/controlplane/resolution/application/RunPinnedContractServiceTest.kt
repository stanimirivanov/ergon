package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.ergon.resolution.domain.ResolutionRunStart
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RunPinnedContractServiceTest {
    private val tenant = TenantId(UUID(0, 1))
    private val runId = ResolutionRunId(UUID(0, 2))
    private val actor = HumanActorId(UUID(0, 3))
    private val at = Instant.parse("2026-10-20T08:00:00Z")
    private val identity =
        ResolutionContractIdentity(ResolutionContractKey.of("restore-access"), ResolutionContractRevision.of(1))
    private val step = ResolutionStepId.of("unlock")
    private val capability = CapabilityName.of("identity.account.unlock")
    private val assignments = Mockito.mock(RunSupervisorAssignmentRepository::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val runs = Mockito.mock(ResolutionRunRepository::class.java)
    private val contracts = Mockito.mock(ResolutionContractRevisionRepository::class.java)
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
        RunPinnedContractService(
            assignments,
            authorities,
            runs,
            contracts,
            transactions,
            Clock.fixed(at, ZoneOffset.UTC),
        )
    private val start =
        ResolutionRunStart(
            runId,
            CaseId(UUID(0, 4)),
            3,
            identity,
            ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
            step,
            capability,
            StepRisk.HIGH,
            ApprovalRequirement.RESOLVER,
            ResolutionRunInitialState.WAITING_FOR_APPROVAL,
        )

    @Test
    fun `missing resolver authority prevents all supervision and private reads`() {
        assertThatThrownBy { read() }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at)
        Mockito.verifyNoInteractions(assignments, runs, contracts)
    }

    @Test
    fun `missing current assignment prevents private run and contract reads`() {
        resolver()
        assertThatThrownBy { read() }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(runs, contracts)
    }

    @Test
    fun `exact gates precede immutable reads and declared safeguards need not equal strengthened policy`() {
        authorize()
        val revision = StoredResolutionContractRevision(definition(), at.plusSeconds(5))
        Mockito.`when`(contracts.find(tenant, identity.key, identity.revision)).thenAnswer {
            check(active)
            revision
        }
        val result = read()
        assertThat(result).isEqualTo(
            RunPinnedContract(
                runId,
                RunSupervisionRevision(4),
                at,
                step,
                revision,
                start.caseId,
                start.caseStreamVersion,
            ),
        )
        assertThat(
            result.revision.contract.steps
                .single()
                .risk,
        ).isEqualTo(StepRisk.LOW)
        assertThat(
            result.revision.contract.steps
                .single()
                .approval,
        ).isEqualTo(ApprovalRequirement.NONE)
        val order = Mockito.inOrder(authorities, assignments, runs, contracts)
        order.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at)
        order.verify(assignments).findAssigned(tenant, runId, actor, at)
        order.verify(runs).find(tenant, runId)
        order.verify(contracts).find(tenant, identity.key, identity.revision)
        order.verifyNoMoreInteractions()
        assertThat(active).isFalse()
    }

    @Test
    fun `missing or wrong run fails closed without reading a contract`() {
        authorize()
        Mockito.`when`(runs.find(tenant, runId)).thenReturn(null)
        assertThatThrownBy { read() }.isInstanceOf(IllegalStateException::class.java)
        Mockito.`when`(runs.find(tenant, runId)).thenReturn(
            StoredResolutionRunStart(start.copy(id = ResolutionRunId(UUID(0, 9))), at),
        )
        assertThatThrownBy { read() }.isInstanceOf(IllegalStateException::class.java)
        Mockito.verifyNoInteractions(contracts)
    }

    @Test
    fun `missing exact revision is corruption not a successful absent contract`() {
        authorize()
        assertThatThrownBy { read() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(active).isFalse()
    }

    @Test
    fun `mismatched revision and pinned capability are rejected`() {
        authorize()
        for (definition in listOf(
            definition(revision = 2),
            definition(stepId = ResolutionStepId.of("another-step")),
            definition(requested = CapabilityName.of("identity.account.inspect")),
        )) {
            Mockito
                .`when`(contracts.find(tenant, identity.key, identity.revision))
                .thenReturn(StoredResolutionContractRevision(definition, at))
            assertThatThrownBy { read() }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `storage failures propagate without fabrication and retire the transaction`() {
        authorize()
        val failure = IllegalStateException("PRIVATE_STORAGE_DIAGNOSTIC")
        Mockito.`when`(contracts.find(tenant, identity.key, identity.revision)).thenThrow(failure)
        assertThatThrownBy { read() }.isSameAs(failure)
        assertThat(active).isFalse()
    }

    private fun read(): RunPinnedContract = service.get(tenant.value, runId.value, actor.value)

    private fun resolver() {
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at))
            .thenReturn(Mockito.mock(StoredApprovalAuthorityEvidence::class.java))
    }

    private fun authorize() {
        resolver()
        Mockito.`when`(assignments.findAssigned(tenant, runId, actor, at)).thenReturn(
            CurrentRunSupervision(
                Mockito.mock(StoredRunSupervisorAssignment::class.java),
                actor,
                RunSupervisionRevision(4),
                at,
            ),
        )
        Mockito.`when`(runs.find(tenant, runId)).thenReturn(StoredResolutionRunStart(start, at))
    }

    private fun definition(
        revision: Int = 1,
        stepId: ResolutionStepId = step,
        requested: CapabilityName = capability,
    ): ResolutionContract {
        val fact = ContractFactType.of("account.access.state")
        return ResolutionContract.define(
            identity.copy(revision = ResolutionContractRevision.of(revision)),
            FactCondition(fact, ContractFactValue.of("LOCKED")),
            listOf(fact),
            listOf(ResolutionStep(stepId, requested, StepRisk.LOW, ApprovalRequirement.NONE)),
            FactCondition(fact, ContractFactValue.of("ACTIVE")),
        )
    }
}
