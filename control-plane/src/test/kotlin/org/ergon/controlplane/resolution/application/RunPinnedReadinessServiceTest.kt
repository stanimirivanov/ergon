package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.DocumentaryBundleMaterial
import org.ergon.resolution.domain.DocumentaryBundlePin
import org.ergon.resolution.domain.DocumentaryRequirementStatus
import org.ergon.resolution.domain.PinnedDocumentaryBundle
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class RunPinnedReadinessServiceTest {
    private val tenant = UUID.randomUUID()
    private val run = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val case = CaseId(UUID.randomUUID())
    private val at = Instant.parse("2026-10-21T08:00:00Z")
    private val contracts = Mockito.mock(RunPinnedContractService::class.java)
    private val reader = Mockito.mock(PinnedReadinessEvidenceReader::class.java)
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
    private val documentary = Mockito.mock(PinnedDocumentaryEvidenceReader::class.java)
    private val service = RunPinnedReadinessService(contracts, reader, transactions, documentary)
    private val property = ContractFactType.of("account.access.state")

    @Test
    fun `denied access prevents private evidence lookup and retires the transaction`() {
        Mockito.`when`(contracts.get(tenant, run, actor)).thenThrow(AssignedRunNotFoundException())
        assertThatThrownBy { service.get(tenant, run, actor) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        Mockito.verifyNoInteractions(reader, documentary)
        assertThat(active).isFalse()
    }

    @Test
    fun `exact pinned evidence evaluates ready within the transaction without timestamp causality`() {
        val pin = pin()
        val fact = fact(AccountAccessState.LOCKED)
        Mockito.`when`(contracts.get(tenant, run, actor)).thenAnswer {
            check(active)
            pin
        }
        Mockito.`when`(reader.findLatest(TenantId(tenant), case, 3)).thenAnswer {
            check(active)
            PinnedReadinessEvidence(case.value, 3, fact)
        }
        val result = service.get(tenant, run, actor)
        assertThat(result.pin).isEqualTo(pin)
        assertThat(result.readiness).isInstanceOf(ResolutionReadiness.Ready::class.java)
        assertThat(result.selectedEvidence).containsExactly(fact)
        assertThat(active).isFalse()
    }

    @Test
    fun `missing requirements precede mismatch and preserve contract declaration order`() {
        val unknown = listOf(ContractFactType.of("z.missing"), property, ContractFactType.of("a.missing"))
        val result = assess(pin(required = unknown), fact(AccountAccessState.ACTIVE))
        assertThat((result.readiness as ResolutionReadiness.MissingEvidence).facts)
            .containsExactly(unknown[0], unknown[2])
        assertThat(result.selectedEvidence).hasSize(1)
    }

    @Test
    fun `unsupported requirements never disclose unrelated supported evidence`() {
        val unknown = ContractFactType.of("other.state")
        val result = assess(pin(required = listOf(unknown)), fact(AccountAccessState.LOCKED))
        assertThat(result.readiness).isInstanceOf(ResolutionReadiness.MissingEvidence::class.java)
        assertThat(result.selectedEvidence).isEmpty()
    }

    @Test
    fun `null evidence is missing while exact case-sensitive mismatch is not applicability`() {
        assertThat(assess(pin(), null).readiness).isInstanceOf(ResolutionReadiness.MissingEvidence::class.java)
        assertThat(assess(pin(expected = "locked"), fact(AccountAccessState.LOCKED)).readiness)
            .isInstanceOf(ResolutionReadiness.NotApplicable::class.java)
    }

    @Test
    fun `missing wrong and out-of-range boundaries fail closed without treating storage errors as absence`() {
        Mockito.`when`(contracts.get(tenant, run, actor)).thenReturn(pin())
        for (snapshot in listOf(
            null,
            PinnedReadinessEvidence(UUID.randomUUID(), 3, null),
            PinnedReadinessEvidence(case.value, 4, null),
        )) {
            Mockito.`when`(reader.findLatest(TenantId(tenant), case, 3)).thenReturn(snapshot)
            assertThatThrownBy { service.get(tenant, run, actor) }.isInstanceOf(IllegalStateException::class.java)
            assertThat(active).isFalse()
        }
        Mockito.`when`(reader.findLatest(TenantId(tenant), case, 3)).thenThrow(IllegalStateException("storage"))
        assertThatThrownBy { service.get(tenant, run, actor) }.hasMessage("storage")
        assertThatThrownBy { PinnedReadinessEvidence(case.value, 2, fact(AccountAccessState.LOCKED)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `documentary reads use the exact run prefix and report absent post prefix attachments`() {
        val reference = DocumentaryBundleRequirement(UUID.randomUUID(), "a".repeat(64))
        val base = pin()
        val contract = base.revision.contract.requiringDocumentaryEvidence(listOf(reference))
        val selected = base.copy(revision = StoredResolutionContractRevision(contract, at))
        Mockito.`when`(contracts.get(tenant, run, actor)).thenReturn(selected)
        Mockito
            .`when`(reader.findLatest(TenantId(tenant), case, 3))
            .thenReturn(PinnedReadinessEvidence(case.value, 3, fact(AccountAccessState.LOCKED)))
        Mockito.`when`(documentary.find(TenantId(tenant), case, 3)).thenReturn(emptyList())
        val absent = service.get(tenant, run, actor)
        assertThat(absent.documentaryEvidence.single().status).isEqualTo(DocumentaryRequirementStatus.MISSING)
        val bundle =
            PinnedDocumentaryBundle(
                DocumentaryBundlePin(reference, 2, at),
                DocumentaryBundleMaterial(reference, at, emptyList()),
            )
        Mockito.`when`(documentary.find(TenantId(tenant), case, 3)).thenReturn(listOf(bundle))
        val ready = service.get(tenant, run, actor)
        assertThat(ready.readiness).isInstanceOf(ResolutionReadiness.Ready::class.java)
        assertThat(ready.documentaryEvidence.single().status).isEqualTo(DocumentaryRequirementStatus.SATISFIED)
        Mockito.verify(documentary, Mockito.times(2)).find(TenantId(tenant), case, 3)
    }

    private fun assess(
        pin: RunPinnedContract,
        fact: PinnedAccountAccessFact?,
    ): RunPinnedReadiness {
        Mockito.`when`(contracts.get(tenant, run, actor)).thenReturn(pin)
        Mockito
            .`when`(reader.findLatest(TenantId(tenant), case, 3))
            .thenReturn(PinnedReadinessEvidence(case.value, 3, fact))
        return service.get(tenant, run, actor)
    }

    private fun pin(
        expected: String = "LOCKED",
        required: List<ContractFactType> = listOf(property),
    ): RunPinnedContract {
        val step = ResolutionStepId.of("unlock")
        val condition = FactCondition(required.first(), ContractFactValue.of(expected))
        val definition =
            ResolutionContract.define(
                ResolutionContractIdentity(ResolutionContractKey.of("access"), ResolutionContractRevision.of(1)),
                condition,
                required,
                listOf(
                    ResolutionStep(
                        step,
                        CapabilityName.of("identity.unlock"),
                        StepRisk.LOW,
                        ApprovalRequirement.NONE,
                    ),
                ),
                condition,
            )
        return RunPinnedContract(
            ResolutionRunId(run),
            RunSupervisionRevision(2),
            at,
            step,
            StoredResolutionContractRevision(definition, at.plusSeconds(10)),
            case,
            3,
        )
    }

    private fun fact(state: AccountAccessState): PinnedAccountAccessFact =
        PinnedAccountAccessFact(
            UUID.randomUUID(),
            3,
            "account",
            state,
            at,
            at.minusSeconds(1),
            PinnedEvidenceSource(
                UUID.randomUUID(),
                2,
                ObservationOriginType.CONNECTOR,
                "stub",
                "account",
                "inert <b>source</b>",
                at.plusSeconds(1),
                at.minusSeconds(2),
            ),
        )
}
