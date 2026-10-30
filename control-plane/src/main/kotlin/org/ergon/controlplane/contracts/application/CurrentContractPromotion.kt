package org.ergon.controlplane.contracts.application

import org.ergon.contracts.domain.ResolutionContractIdentity
import java.util.UUID

/** Contract-owned admission query; the caller's transaction retains the governing-contract lock. */
fun interface CurrentContractPromotion {
    /** A historical receipt qualifies only under the current exact required-suite binding version. */
    fun existsWhileLocked(
        tenant: UUID,
        contract: ResolutionContractIdentity,
    ): Boolean
}
