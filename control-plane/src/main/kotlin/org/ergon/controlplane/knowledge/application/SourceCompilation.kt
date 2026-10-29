package org.ergon.controlplane.knowledge.application

import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.SourceObservation
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId

/** Immutable-source projection, not a persisted review decision or accepted semantic evidence. */
data class SourceCompilation(
    val extraction: EvidenceExtraction,
    val observations: List<SourceObservation>,
)

/** Protocol parsing boundary; adapters must verify citations and reject limits without partial output. */
fun interface SourceObservationParser {
    /** Throws [InvalidSource] for unsupported formats or exceeded deterministic resource limits. */
    fun parse(source: EvidenceSource): SourceCompilation
}

/** Rechecks existing source access before synchronous parsing; no external effects or new stored state. */
class SourceCompilationService(
    private val sources: SourceRevisionService,
    private val parser: SourceObservationParser,
) {
    /** Exact stored revision only; authorization/storage failures propagate before the parser runs. */
    fun compile(
        revision: SourceRevision,
        actor: HumanActorId,
    ): SourceCompilation = parser.parse(sources.get(revision, actor).source)
}
