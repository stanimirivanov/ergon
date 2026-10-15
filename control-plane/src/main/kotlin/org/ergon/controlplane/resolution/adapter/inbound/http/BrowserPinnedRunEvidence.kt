package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.PinnedRunEvidence
import java.time.Instant
import java.util.UUID

/** Bounded pinned binding history, never the latest case state or an independent proof assessment. */
data class BrowserPinnedRunEvidence(
    val caseId: UUID,
    val caseStreamVersion: Long,
    val facts: List<BrowserPinnedEvidenceFact>,
    val truncated: Boolean,
)

data class BrowserPinnedEvidenceFact(
    val factId: UUID,
    val streamVersion: Long,
    val property: String,
    val accountReference: String,
    val value: String,
    val boundAt: Instant,
    val recordedAt: Instant,
    val source: BrowserPinnedEvidenceSource,
)

/** Only a disclosed fact's exact source, including untrusted plain text; not an instruction or a fetchable URL. */
data class BrowserPinnedEvidenceSource(
    val observationId: UUID,
    val streamVersion: Long,
    val originType: String,
    val provider: String,
    val reference: String?,
    val content: String,
    val observedAt: Instant,
    val recordedAt: Instant,
)

internal fun PinnedRunEvidence.toBrowserEvidence(): BrowserPinnedRunEvidence =
    BrowserPinnedRunEvidence(
        caseId,
        caseStreamVersion,
        facts.map { fact ->
            val source = fact.source
            BrowserPinnedEvidenceFact(
                fact.factId,
                fact.streamVersion,
                fact.property,
                fact.accountReference,
                fact.value.name,
                fact.boundAt,
                fact.recordedAt,
                BrowserPinnedEvidenceSource(
                    source.observationId,
                    source.streamVersion,
                    source.originType.name,
                    source.provider,
                    source.reference,
                    source.content,
                    source.observedAt,
                    source.recordedAt,
                ),
            )
        },
        truncated,
    )
