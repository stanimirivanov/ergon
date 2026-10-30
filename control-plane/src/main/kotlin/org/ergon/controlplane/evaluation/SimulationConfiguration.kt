package org.ergon.controlplane.evaluation

import org.ergon.controlplane.evaluation.application.SimulationFixtureDecoder
import org.ergon.controlplane.evaluation.application.SimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.SimulationFixtureService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SimulationConfiguration {
    @Bean
    fun simulationFixtureService(
        decoder: SimulationFixtureDecoder,
        repository: SimulationFixtureRepository,
    ) = SimulationFixtureService(decoder, repository)
}
