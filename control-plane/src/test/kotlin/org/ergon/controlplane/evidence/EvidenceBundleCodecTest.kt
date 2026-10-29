package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.serialization.bundleDigest
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.UUID

class EvidenceBundleCodecTest {
    private val b = EvidenceBundleFixtures()

    @Test
    fun `canonical manifest and command survive exact round trips with deterministic digest`() {
        val text = b.codec.encode(b.f.source.tenant, b.manifest)
        assertThat(b.codec.decode(b.f.source.tenant, text)).isEqualTo(b.manifest)
        assertThat(b.codec.encode(b.f.source.tenant, b.codec.decode(b.f.source.tenant, text))).isEqualTo(text)
        assertThat(bundleDigest(text)).matches("[a-f0-9]{64}")
        assertThat(b.codec.decodeCommand(b.command.id, b.codec.encodeCommand(b.command))).isEqualTo(b.command)
    }

    @Test
    fun `unsupported foreign corrupt and noncanonical durable shapes fail as integrity errors`() {
        val tenant = b.f.source.tenant
        val text = b.codec.encode(tenant, b.manifest)
        for (bad in listOf(
            text.replace("evidence-bundle/v1", "evidence-bundle/v2"),
            text + " {}",
            "{broken",
            text.replace("CONFLICT", "UNDETERMINED"),
        )) {
            assertThatThrownBy { b.codec.decode(tenant, bad) }.isInstanceOf(IllegalStateException::class.java)
        }
        assertThatThrownBy { b.codec.decode(TenantId(UUID.randomUUID()), text) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
