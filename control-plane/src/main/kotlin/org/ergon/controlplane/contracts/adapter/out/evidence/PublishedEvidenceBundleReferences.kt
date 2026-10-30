package org.ergon.controlplane.contracts.adapter.out.evidence

import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.contracts.application.PublishedContractBundles
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.identity.domain.TenantId
import org.springframework.stereotype.Component

/** Internal machine publication uses the existing evidence repository's integrity-checked historical read port. */
@Component
class PublishedEvidenceBundleReferences(
    private val bundles: EvidenceBundleRepository,
) : PublishedContractBundles {
    override fun contains(
        tenant: TenantId,
        requirement: DocumentaryBundleRequirement,
    ): Boolean = bundles.find(tenant, requirement.bundleId)?.sha256 == requirement.sha256
}
