package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.ResolutionContractIdentity
import java.util.UUID

/** Called only for fresh starts, in the same transaction as run persistence; never for historical replay. */
fun interface RunPromotionAdmission {
    fun requireCurrent(
        tenant: UUID,
        contract: ResolutionContractIdentity,
    )
}

/** Stable refusal without disclosing governance actors or private report coordinates. */
class ResolutionRunPromotionRequiredException :
    RuntimeException(
        "The pinned contract revision requires promotion under the current required-suite binding " +
            "before a new run can start",
    )
