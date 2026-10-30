package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.adapter.inbound.document.SnakeYamlResolutionContractDocumentDecoder
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.controlplane.evaluation.adapter.out.persistence.PublishedEvaluationContractAdapter
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PublishedEvaluationContractTest {
    @Test
    fun `same contract identity with changed meaning is refused while YAML spelling is irrelevant`() {
        val f = PinnedScenarioFixtures()
        val decoder = SnakeYamlResolutionContractDocumentDecoder()
        val original = decoder.decode(f.contract)
        val stored = StoredResolutionContractRevision(original, Instant.EPOCH)
        val repository =
            object : ResolutionContractRevisionRepository {
                override fun publish(
                    tenantId: TenantId,
                    contract: ResolutionContract,
                ) = error("unused")

                override fun find(
                    tenantId: TenantId,
                    key: ResolutionContractKey,
                    revision: ResolutionContractRevision,
                ): StoredResolutionContractRevision? =
                    if (tenantId.value == f.tenant && key == original.key && revision == original.revision) {
                        stored
                    } else {
                        null
                    }
            }
        val adapter = PublishedEvaluationContractAdapter(repository, ResolutionContractJsonCodec(f.mapper), f.mapper)
        assertThat(adapter.matches(f.tenant, decoder.decode("# different spelling\n" + f.contract))).isTrue()
        assertThat(adapter.matches(f.tenant, decoder.decode(f.contract.replace("equals: ACTIVE", "equals: DISABLED"))))
            .isFalse()
        val laterRevision = decoder.decode(f.contract.replace("revision: 1", "revision: 2"))
        assertThat(adapter.matches(f.tenant, laterRevision)).isFalse()
        assertThat(adapter.matches(UUID.randomUUID(), original)).isFalse()
    }
}
