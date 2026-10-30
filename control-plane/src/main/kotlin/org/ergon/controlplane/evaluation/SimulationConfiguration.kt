package org.ergon.controlplane.evaluation

import org.ergon.controlplane.evaluation.application.CapabilityScenarioDecoder
import org.ergon.controlplane.evaluation.application.CapabilityScenarioReplay
import org.ergon.controlplane.evaluation.application.ModelProposalDecoder
import org.ergon.controlplane.evaluation.application.ModelProposalScenario
import org.ergon.controlplane.evaluation.application.PinnedScenarioDecoder
import org.ergon.controlplane.evaluation.application.PinnedScenarioReplay
import org.ergon.controlplane.evaluation.application.ScenarioInvocationRunner
import org.ergon.controlplane.evaluation.application.SimulationFixtureDecoder
import org.ergon.controlplane.evaluation.application.SimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.SimulationFixtureService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SimulationConfiguration {
    @Bean
    fun modelProposalScenario(decoder: ModelProposalDecoder) = ModelProposalScenario(decoder)

    @Bean
    fun capabilityScenarioReplay(
        decoder: CapabilityScenarioDecoder,
        invocations: ScenarioInvocationRunner,
    ) = CapabilityScenarioReplay(decoder, invocations)

    @Bean
    fun pinnedScenarioReplay(decoder: PinnedScenarioDecoder) = PinnedScenarioReplay(decoder)

    @Bean
    fun simulationFixtureService(
        decoder: SimulationFixtureDecoder,
        repository: SimulationFixtureRepository,
    ) = SimulationFixtureService(decoder, repository)
}
