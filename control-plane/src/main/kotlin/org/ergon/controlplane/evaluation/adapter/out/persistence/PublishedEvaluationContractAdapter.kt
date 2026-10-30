package org.ergon.controlplane.evaluation.adapter.out.persistence

import org.ergon.contracts.domain.ResolutionContract
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.evaluation.application.PublishedEvaluationContract
import org.ergon.identity.domain.TenantId
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Compare normalized published meaning, not YAML spelling or PostgreSQL JSONB serialization order. */
@Component
class PublishedEvaluationContractAdapter(
    private val contracts: ResolutionContractRevisionRepository,
    private val codec: ResolutionContractJsonCodec,
    private val mapper: JsonMapper,
) : PublishedEvaluationContract {
    override fun matches(
        tenant: UUID,
        contract: ResolutionContract,
    ): Boolean {
        val published = contracts.find(TenantId(tenant), contract.key, contract.revision) ?: return false
        val expected = codec.encode(published.contract)
        val actual = codec.encode(contract)
        return expected.schema == actual.schema &&
            mapper.readTree(expected.definition) == mapper.readTree(actual.definition)
    }
}
