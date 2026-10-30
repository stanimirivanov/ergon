package org.ergon.controlplane.cases.adapter.inbound.http

import org.ergon.resolution.domain.DocumentaryRequirementAssessment
import java.time.Instant
import java.util.UUID

/** Exact requirement and attachment-time result, never current authority or live account state. */
data class DocumentaryReadinessResponse(
    val bundleId: UUID,
    val sha256: String,
    val status: String,
    val reason: String?,
    val pinStreamVersion: Long?,
    val attachedAt: Instant?,
)

internal fun DocumentaryRequirementAssessment.toDocumentaryResponse() =
    DocumentaryReadinessResponse(
        requirement.bundleId,
        requirement.sha256,
        status.name,
        reason?.name,
        pin?.streamVersion,
        pin?.attachedAt,
    )
