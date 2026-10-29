package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.knowledge.application.SourceCompilation
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.DocumentedOperation
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.ProcedureContent
import org.ergon.evidence.domain.ProcedureProposal
import org.ergon.evidence.domain.ProcedureStep
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceObservation
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

/** Parser-owned syntax outline; semantic binding never imports CommonMark infrastructure. */
fun interface ProcedureOutlineParser {
    fun steps(
        source: EvidenceSource,
        selected: SourceCitation,
    ): List<SourceCitation>
}

data class ProcedureOperationBinding(
    val position: Int,
    val source: SourceRevision,
    val extractionId: UUID,
    val observationIndex: Int,
)

data class ProcedureCommand(
    val id: UUID,
    val extractionId: UUID,
    val observationIndex: Int,
    val title: String,
    val operations: List<ProcedureOperationBinding> = emptyList(),
)

data class StoredProcedureProposal(
    val proposal: ProcedureProposal,
    val proposedBy: HumanActorId,
    val recordedAt: Instant,
)

data class ProcedureRegistration(
    val stored: StoredProcedureProposal,
    val created: Boolean,
)

/** Atomically writes immutable definitions and operation-source links; changed same-ID commands conflict. */
interface ProcedureProposalRepository {
    fun register(
        proposal: ProcedureProposal,
        actor: HumanActorId,
    ): ProcedureRegistration

    fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredProcedureProposal?
}

class InvalidProcedure : RuntimeException()

class ProcedureConflict : RuntimeException()

/** Manual procedure binding, with source authorization before parsing, reference access and persistence. */
class ProcedureProposalService(
    private val sources: SourceRevisionService,
    private val markdown: SourceCompilationService,
    private val openApi: SourceCompilationService,
    private val outline: ProcedureOutlineParser,
    private val repository: ProcedureProposalRepository,
) {
    fun propose(
        source: SourceRevision,
        actor: HumanActorId,
        command: ProcedureCommand,
    ): ProcedureRegistration {
        val compiled = markdown.compile(source, actor)
        val list = select(compiled, command.observationIndex, command.extractionId, SourceObservationKind.ORDERED_LIST)
        val original = sources.get(source, actor).source
        check(list.citation.verifyAgainst(original)) { "compiled procedure citation is inconsistent" }
        val citations = outline.steps(original, list.citation)
        validateBindings(command.operations, source, citations.size)
        val bindings = command.operations.associate { it.position to operation(it, actor) }
        val steps =
            citations.mapIndexed { index, citation ->
                check(citation.verifyAgainst(original)) { "compiled step citation is inconsistent" }
                ProcedureStep(index + 1, citation, bindings[index + 1])
            }
        val content =
            try {
                ProcedureContent.create(command.title, list.citation, steps)
            } catch (_: IllegalArgumentException) {
                throw InvalidProcedure()
            }
        val proposal = ProcedureProposal(command.id, compiled.extraction, command.observationIndex, content)
        return repository.register(proposal, actor)
    }

    /** Reauthorizes exact-source reads and verifies historical citations without reinterpretation. */
    fun get(
        source: SourceRevision,
        actor: HumanActorId,
        id: UUID,
    ): StoredProcedureProposal {
        val original = sources.get(source, actor).source
        val stored = repository.find(source.tenantId, id) ?: throw SourceUnavailable()
        val content = stored.proposal.content
        if (content.citation.revision != source) throw SourceUnavailable()
        check(content.citation.verifyAgainst(original)) { "stored procedure citation is inconsistent" }
        for (step in content.steps) {
            check(step.citation.verifyAgainst(original)) { "stored step citation is inconsistent" }
            step.operation?.let { operation ->
                val api = sources.get(operation.citation.revision, actor).source
                check(operation.citation.verifyAgainst(api)) { "stored operation citation is inconsistent" }
            }
        }
        return stored
    }

    private fun validateBindings(
        bindings: List<ProcedureOperationBinding>,
        source: SourceRevision,
        count: Int,
    ) {
        val uniquePositions = bindings.map { it.position }.distinct().size
        if (bindings.size > count || uniquePositions != bindings.size) throw InvalidProcedure()
        if (bindings.any { it.position !in 1..count || it.source.tenantId != source.tenantId }) throw InvalidProcedure()
    }

    private fun operation(
        binding: ProcedureOperationBinding,
        actor: HumanActorId,
    ): DocumentedOperation {
        val compiled = openApi.compile(binding.source, actor)
        val kind = SourceObservationKind.API_OPERATION
        val selected = select(compiled, binding.observationIndex, binding.extractionId, kind)
        val source = sources.get(binding.source, actor).source
        check(selected.citation.verifyAgainst(source)) { "compiled operation citation is inconsistent" }
        val pointer = checkNotNull(selected.jsonPointer) { "operation pointer is missing" }
        return try {
            DocumentedOperation(compiled.extraction, binding.observationIndex, selected.citation, pointer)
        } catch (_: IllegalArgumentException) {
            throw InvalidProcedure()
        }
    }

    private fun select(
        compiled: SourceCompilation,
        index: Int,
        extraction: UUID,
        kind: SourceObservationKind,
    ): SourceObservation {
        val selected = compiled.observations.getOrNull(index) ?: throw InvalidProcedure()
        if (compiled.extraction.id != extraction || selected.kind != kind) throw InvalidProcedure()
        val sameSource = selected.citation.revision == compiled.extraction.source
        check(sameSource) { "compiled observation source is inconsistent" }
        return selected
    }
}
