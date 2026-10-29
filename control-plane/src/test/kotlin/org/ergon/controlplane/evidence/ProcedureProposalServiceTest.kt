package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.InvalidProcedure
import org.ergon.controlplane.evidence.application.ProcedureProposalRepository
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.evidence.application.ProcedureRegistration
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkProcedureOutline
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.evidence.domain.SourceObservationKind
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class ProcedureProposalServiceTest {
    private val f = ProcedureFixtures()
    private val sources = Mockito.mock(SourceRevisionService::class.java)
    private val markdown = Mockito.mock(SourceCompilationService::class.java)
    private val api = Mockito.mock(SourceCompilationService::class.java)
    private val repository = Mockito.mock(ProcedureProposalRepository::class.java)
    private val service = ProcedureProposalService(sources, markdown, api, CommonMarkProcedureOutline(), repository)

    @Test
    fun `explicit operation references and unlinked steps preserve attributed order through write and read`() {
        allowSources()
        val proposal = f.proposal()
        val stored = StoredProcedureProposal(proposal, f.actor, Instant.EPOCH)
        Mockito.`when`(repository.register(proposal, f.actor)).thenReturn(ProcedureRegistration(stored, true))
        assertThat(service.propose(f.markdown.revision, f.actor, f.command).stored).isEqualTo(stored)
        Mockito.`when`(repository.find(f.tenant, proposal.id)).thenReturn(stored)
        assertThat(service.get(f.markdown.revision, f.actor, proposal.id)).isEqualTo(stored)
        Mockito.doThrow(SourceUnavailable()).`when`(sources).get(f.api.revision, f.actor)
        assertThatThrownBy { service.get(f.markdown.revision, f.actor, proposal.id) }
            .isInstanceOf(SourceUnavailable::class.java)
    }

    @Test
    fun `authorization and exact list selection precede operation reads and proposal writes`() {
        Mockito.`when`(markdown.compile(f.markdown.revision, f.actor)).thenThrow(SourceUnavailable())
        assertThatThrownBy { service.propose(f.markdown.revision, f.actor, f.command) }
            .isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(repository, api)
        allowSources()
        for (command in listOf(
            f.command.copy(extractionId = UUID(0, 9)),
            f.command.copy(observationIndex = 0),
            f.command.copy(observationIndex = -1),
            f.command.copy(title = " "),
            f.command.copy(operations = f.command.operations + f.command.operations),
            f.command.copy(operations = f.command.operations.map { it.copy(position = 3) }),
            f.command.copy(
                operations =
                    f.command.operations.map {
                        it.copy(
                            source =
                                it.source.copy(
                                    tenantId =
                                        org.ergon.identity.domain
                                            .TenantId(UUID(0, 9)),
                                ),
                        )
                    },
            ),
        )) {
            assertThatThrownBy { service.propose(f.markdown.revision, f.actor, command) }
                .isInstanceOf(InvalidProcedure::class.java)
        }
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun `missing stale and non operation API selections cannot create proposals`() {
        allowSources()
        val responseIndex = f.compiledApi.observations.indexOfFirst { it.kind == SourceObservationKind.API_RESPONSE }
        for (binding in listOf(
            f.command.operations
                .single()
                .copy(extractionId = UUID(0, 9)),
            f.command.operations
                .single()
                .copy(observationIndex = responseIndex),
            f.command.operations
                .single()
                .copy(observationIndex = 999),
        )) {
            val command = f.command.copy(operations = listOf(binding))
            assertThatThrownBy { service.propose(f.markdown.revision, f.actor, command) }
                .isInstanceOf(InvalidProcedure::class.java)
        }
        Mockito.doThrow(SourceUnavailable()).`when`(api).compile(f.api.revision, f.actor)
        assertThatThrownBy { service.propose(f.markdown.revision, f.actor, f.command) }
            .isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(repository)
    }

    private fun allowSources() {
        Mockito.doReturn(f.compiled).`when`(markdown).compile(f.markdown.revision, f.actor)
        Mockito.doReturn(f.compiledApi).`when`(api).compile(f.api.revision, f.actor)
        Mockito.`when`(sources.get(f.markdown.revision, f.actor)).thenReturn(
            StoredSourceRevision(
                f.markdown,
                f.actor,
                Instant.EPOCH,
            ),
        )
        Mockito.`when`(sources.get(f.api.revision, f.actor)).thenReturn(
            StoredSourceRevision(
                f.api,
                f.actor,
                Instant.EPOCH,
            ),
        )
    }
}
