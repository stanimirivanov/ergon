package org.ergon.controlplane.knowledge

import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.identity.domain.TenantId
import java.util.UUID

/** Checked-in, licensed bytes only; upstream URIs are never dereferenced by acceptance tests. */
internal object CompilerAcceptanceFixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(
            javaClass.getResource("/knowledge/acceptance/$name"),
        ).readBytes()

    fun source(
        tenant: TenantId,
        name: String,
    ): EvidenceSource =
        EvidenceSource.fromUtf8(
            tenant,
            SourceId(UUID.randomUUID()),
            if (name.endsWith(".json")) EvidenceSourceFormat.OPENAPI_3_0_JSON else EvidenceSourceFormat.COMMONMARK,
            bytes(name),
        )
}
