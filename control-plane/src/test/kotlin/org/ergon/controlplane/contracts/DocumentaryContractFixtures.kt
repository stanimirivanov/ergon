package org.ergon.controlplane.contracts

import org.ergon.controlplane.contracts.adapter.inbound.document.SnakeYamlResolutionContractDocumentDecoder
import java.util.UUID

internal object DocumentaryContractFixtures {
    val bundleId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val digest = "a".repeat(64)
    val legacy = requireNotNull(javaClass.getResource("/contracts/restore-workspace-access.yaml")).readText()
    val document =
        requireNotNull(
            javaClass.getResource("/contracts/restore-workspace-access-with-documentary-evidence.yaml"),
        ).readText()
    val decoder = SnakeYamlResolutionContractDocumentDecoder()

    fun document(
        id: UUID,
        sha256: String,
    ): String = document.replace(bundleId.toString(), id.toString()).replace(digest, sha256)
}
