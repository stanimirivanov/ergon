package org.ergon.controlplane.evaluation

import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationManifestDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationMaterialDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationProvenanceDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationProvenanceKind
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationCasePrefix
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.evaluation.domain.SimulationFixture
import java.util.UUID

internal object SimulationPackageFixtures {
    fun document(tenant: UUID = UUID.randomUUID()): SimulationPackageDocument {
        // SHA-256 of the exact UTF-8 bytes {}. Material semantics are deliberately deferred to replay.
        val digest = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        val artifacts =
            listOf("contract", "policy", "case", "evaluator", "assertions")
                .map { SimulationArtifact(it, "1", digest) }
        val manifest =
            SimulationManifestDocument(
                SimulationFixture.VERSION,
                tenant,
                artifacts[0],
                artifacts[1],
                SimulationCasePrefix(UUID.randomUUID(), 1, artifacts[2]),
                artifacts[3],
                "2026-10-30T00:00:00Z",
                emptyList(),
                emptyList(),
                null,
                artifacts[4],
                SimulationExpectedOutcome.NOT_READY,
                null,
            )
        return SimulationPackageDocument(
            manifest,
            manifest.toDomain().semanticDigest(),
            artifacts.map { SimulationMaterialDocument(it, "{}") },
            SimulationProvenanceDocument(SimulationProvenanceKind.SYNTHETIC, "Independent synthetic fixture"),
        )
    }
}
