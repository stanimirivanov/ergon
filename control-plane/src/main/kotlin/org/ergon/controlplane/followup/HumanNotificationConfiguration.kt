package org.ergon.controlplane.followup

import org.ergon.controlplane.followup.application.HumanNotificationReader
import org.ergon.controlplane.followup.application.HumanNotificationService
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Composes historical feed policy with its reader and temporal authority; no target access is granted. */
@Configuration(proxyBeanMethods = false)
class HumanNotificationConfiguration {
    @Bean
    fun humanNotifications(
        reader: HumanNotificationReader,
        authorities: HumanAuthorityRepository,
        clock: Clock,
    ) = HumanNotificationService(reader, authorities, clock)
}
