package org.ergon.controlplane.evaluation.adapter.inbound.browser

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.application.AuthorityRevocationIntent
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.ViewerAttestation
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

/** Shared bounded strict JSON transport; no permissive automatic binding of governance commands. */
@Component
class SimulationBrowserDocuments(
    private val strict: PinnedScenarioJsonDecoder,
) {
    fun review(request: HttpServletRequest): SimulationReviewDocument =
        read(request, SimulationReviewDocument::class.java, "ergon.simulation-lab/review/v1")

    fun viewer(request: HttpServletRequest): ViewerAttestation {
        val value = read(request, ViewerAuthorityDocument::class.java, "ergon.evaluation-viewer/authority/v1")
        return ViewerAttestation(
            value.actorId,
            value.sourceProvider,
            value.sourceReference,
            Instant.parse(value.expiresAt),
        )
    }

    fun revocation(request: HttpServletRequest): AuthorityRevocationIntent {
        val value =
            read(
                request,
                SimulationRevocationDocument::class.java,
                "ergon.simulation-authority/revocation/v1",
            )
        return AuthorityRevocationIntent(value.sourceProvider, value.sourceReference, value.reason)
    }

    fun bytes(request: HttpServletRequest): ByteArray {
        val bytes = request.inputStream.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw SimulationFixtureTooLarge()
        return bytes
    }

    private fun <T : Any> read(
        request: HttpServletRequest,
        type: Class<T>,
        schema: String,
    ): T =
        strict.read(
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes(request)))
                .toString(),
            type,
            schema,
        )

    companion object {
        const val MAX_BYTES = 131_072
    }
}

data class SimulationReviewDocument(
    val schemaVersion: String,
    val reports: List<SelectedEvaluationReport>,
    val rationale: String,
)

data class ViewerAuthorityDocument(
    val schemaVersion: String,
    val actorId: UUID,
    val sourceProvider: String,
    val sourceReference: String,
    val expiresAt: String,
)

data class SimulationRevocationDocument(
    val schemaVersion: String,
    val sourceProvider: String,
    val sourceReference: String,
    val reason: String,
)
