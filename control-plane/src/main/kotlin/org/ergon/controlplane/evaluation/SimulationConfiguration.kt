package org.ergon.controlplane.evaluation

import org.ergon.controlplane.evaluation.application.CapabilityScenarioDecoder
import org.ergon.controlplane.evaluation.application.CapabilityScenarioReplay
import org.ergon.controlplane.evaluation.application.EvaluationExecution
import org.ergon.controlplane.evaluation.application.EvaluationProgramDecoder
import org.ergon.controlplane.evaluation.application.EvaluationReportCodec
import org.ergon.controlplane.evaluation.application.EvaluationReportRepository
import org.ergon.controlplane.evaluation.application.EvaluationReportRunner
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
    fun evaluationReportRunner(
        decoder: EvaluationProgramDecoder,
        invocations: ScenarioInvocationRunner,
        codec: EvaluationReportCodec,
    ) = EvaluationReportRunner(decoder, invocations, codec)

    @Bean
    fun evaluationExecution(
        fixtures: SimulationFixtureRepository,
        runner: EvaluationReportRunner,
        reports: EvaluationReportRepository,
    ) = EvaluationExecution(fixtures, runner, reports)

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
