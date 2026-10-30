package org.ergon.controlplane.contracts.application

import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.identity.domain.TenantId

/** Exact immutable historical lookup; never selects latest or requalifies publication history. */
fun interface PublishedContractBundles {
    /** False covers absent, foreign-tenant and digest-mismatched references alike; infrastructure errors propagate. */
    fun contains(
        tenant: TenantId,
        requirement: DocumentaryBundleRequirement,
    ): Boolean
}

/** Unavailable references share one non-disclosing publication failure. */
class InvalidContractDocumentaryEvidenceException(
    val violations: List<ContractDocumentViolation>,
) : RuntimeException("contract documentary evidence is unavailable")
