package org.ergon.controlplane.contracts.adapter.out.registry

import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceRegistry
import org.springframework.stereotype.Component

/**
 * System vocabulary required by the first deterministic access-restoration slice.
 *
 * These names are compatibility boundaries: removing or repurposing one would
 * change the meaning of already published contracts. Tenant connector
 * availability and authorization are deliberately checked elsewhere.
 */
@Component
class BuiltInResolutionContractReferenceRegistry : ResolutionContractReferenceRegistry {
    private val facts = setOf(ContractFactType.of("account.access.state"))
    private val capabilities = setOf(CapabilityName.of("identity.account.unlock"))

    override fun containsFact(fact: ContractFactType): Boolean = fact in facts

    override fun containsCapability(capability: CapabilityName): Boolean = capability in capabilities
}
