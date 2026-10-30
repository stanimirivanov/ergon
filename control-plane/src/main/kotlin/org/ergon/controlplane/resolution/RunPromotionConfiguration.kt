package org.ergon.controlplane.resolution

import org.ergon.controlplane.contracts.application.CurrentContractPromotion
import org.ergon.controlplane.resolution.application.ResolutionRunPromotionRequiredException
import org.ergon.controlplane.resolution.application.RunPromotionAdmission
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/** Explicit deployment scope: all instances must use the same allowlist before admitting tenant traffic. */
@Configuration(proxyBeanMethods = false)
class RunPromotionConfiguration {
    @Bean
    fun runPromotionAdmission(
        current: CurrentContractPromotion,
        @Value("\${ergon.contract-promotion.enforced-tenants:}") configuredTenants: String,
    ): RunPromotionAdmission {
        val tenants =
            if (configuredTenants.isBlank()) {
                emptySet()
            } else {
                configuredTenants
                    .split(',')
                    .map {
                        val value = it.trim()
                        val id = UUID.fromString(value)
                        require(id.toString().equals(value, ignoreCase = true)) {
                            "enforced tenant must be a canonical UUID"
                        }
                        id
                    }.toSet()
            }
        return RunPromotionAdmission { tenant, contract ->
            if (tenant in tenants && !current.existsWhileLocked(tenant, contract)) {
                throw ResolutionRunPromotionRequiredException()
            }
        }
    }
}
