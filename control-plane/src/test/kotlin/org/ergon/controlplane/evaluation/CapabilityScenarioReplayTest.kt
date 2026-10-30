package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.contracts.adapter.inbound.document.SnakeYamlResolutionContractDocumentDecoder
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.evaluation.adapter.inbound.document.CapabilityScenarioDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.CapabilityScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioCallDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioProofDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioRouteDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationFixtureJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationMaterialDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.adapter.out.simulation.ScriptedCapabilityInvocation
import org.ergon.controlplane.evaluation.application.CapabilityScenarioReplay
import org.ergon.controlplane.evaluation.application.CapabilityScenarioResult
import org.ergon.controlplane.evaluation.application.CapabilityScenarioStatus
import org.ergon.controlplane.evaluation.application.CapabilityScenarioTrace
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.ScenarioAuthority
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationScript
import org.ergon.evaluation.domain.SimulationScriptKind
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionOutcomeProofPendingReason
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class CapabilityScenarioReplayTest {
    private val f = PinnedScenarioFixtures()
    private val pinned =
        PinnedScenarioJsonDecoder(
            SimulationFixtureJsonCodec(f.mapper),
            SnakeYamlResolutionContractDocumentDecoder(),
            ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
            f.mapper,
        )
    private val decoder = CapabilityScenarioJsonDecoder(pinned, f.mapper)
    private val replay = CapabilityScenarioReplay(decoder, ScriptedCapabilityInvocation())
    private val base = f.document()
    private val prefix = base.manifest.casePrefix.streamVersion
    private val scenario =
        CapabilityScenarioDocument(
            CapabilityScenarioJsonDecoder.SCHEMA,
            ScenarioAuthority(UUID.randomUUID(), ApprovalAuthority.RESOLVER, null, -1, 60),
            ApprovalDecisionOutcome.APPROVED,
            60,
            0,
            ScenarioRouteDocument("identity.account.unlock", "simulation.identity"),
            listOf(ScenarioCallDocument(CapabilityInvocationCallOutcome.SUCCEEDED, 1_000_000)),
            1,
            prefix + 2,
            listOf(
                ScenarioProofDocument(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "account.access.state",
                    "ACTIVE",
                    prefix + 1,
                    prefix + 2,
                    f.at.plusSeconds(1).toString(),
                    f.at.plusSeconds(1).toString(),
                ),
            ),
        )

    private fun packageBytes(
        script: CapabilityScenarioDocument,
        source: SimulationPackageDocument = base,
    ): ByteArray {
        val evaluator = PinnedScenarioJsonDecoder.CAPABILITY_EVALUATOR_MATERIAL
        val document = f.replaceMaterial(source, source.manifest.evaluator, evaluator)
        val content = f.mapper.writeValueAsString(script)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))
        val artifact = SimulationArtifact("connector-program", "1", digest)
        val scriptReference = SimulationScript(SimulationScriptKind.CONNECTOR, artifact)
        val manifest = document.manifest.copy(scripts = listOf(scriptReference))
        return f.mapper.writeValueAsBytes(
            document.copy(
                manifest = manifest,
                digest = manifest.toDomain().semanticDigest(),
                materials = document.materials + SimulationMaterialDocument(artifact, content),
            ),
        )
    }

    private fun trace(script: CapabilityScenarioDocument = scenario): CapabilityScenarioTrace =
        (replay.replay(packageBytes(script)) as CapabilityScenarioResult.Evaluated).trace

    @Test
    fun `success uses exact grant receipt and independent proof and repeats deterministically`() {
        val first = trace()
        assertThat(first).isEqualTo(trace())
        assertThat(first.status).isEqualTo(CapabilityScenarioStatus.PROOF_ACCEPTED)
        assertThat(first.proof).isInstanceOf(ResolutionOutcomeProofAssessment.Accepted::class.java)
        assertThat(first.receipt?.outcome).isEqualTo(CapabilityInvocationOutcome.SUCCEEDED)
        assertThat(first.idempotencyKeys).containsExactly(first.receipt!!.authorizationConsumptionId.value)
        assertThat(first.receipt.timing?.elapsedNanoseconds).isEqualTo(1_000_000)
    }

    @Test
    fun `missing rejected expired or wrong authority and route never invoke`() {
        val authority = checkNotNull(scenario.authority)
        val expired = authority.copy(untilSeconds = 0)
        val wrong = authority.copy(authority = ApprovalAuthority.REQUESTER, caseId = f.case)
        val variants =
            listOf(
                scenario.copy(decision = null) to CapabilityScenarioStatus.WAITING_FOR_APPROVAL,
                scenario.copy(authority = null) to CapabilityScenarioStatus.WAITING_FOR_APPROVAL,
                scenario.copy(decision = ApprovalDecisionOutcome.REJECTED) to
                    CapabilityScenarioStatus.APPROVAL_REJECTED,
                scenario.copy(authority = expired) to CapabilityScenarioStatus.AUTHORITY_REJECTED,
                scenario.copy(authority = wrong)
                    to CapabilityScenarioStatus.AUTHORITY_REJECTED,
                scenario.copy(route = null) to CapabilityScenarioStatus.ROUTE_UNAVAILABLE,
                scenario.copy(route = scenario.route!!.copy(capability = "identity.other")) to
                    CapabilityScenarioStatus.AUTHORIZATION_REJECTED,
                scenario.copy(consumeAfterSeconds = 60) to CapabilityScenarioStatus.AUTHORIZATION_REJECTED,
            )
        variants.forEach { (script, expected) ->
            val result = trace(script)
            assertThat(result.status).isEqualTo(expected)
            assertThat(result.idempotencyKeys).isEmpty()
            assertThat(result.receipt).isNull()
            assertThat(result.proof).isNull()
        }
    }

    @Test
    fun `uncertainty has no receipt and explicit retry reuses the operation key`() {
        val unknown = ScenarioCallDocument(CapabilityInvocationCallOutcome.UNKNOWN, 2_000_000)
        val pending = trace(scenario.copy(calls = listOf(unknown)))
        assertThat(pending.status).isEqualTo(CapabilityScenarioStatus.INVOCATION_UNKNOWN)
        assertThat(pending.receipt).isNull()
        assertThat(pending.attempts.single().outcome).isEqualTo(CapabilityInvocationCallOutcome.UNKNOWN)
        val retry = trace(scenario.copy(calls = listOf(unknown) + scenario.calls, invocationCount = 2))
        assertThat(retry.idempotencyKeys).hasSize(2)
        assertThat(retry.idempotencyKeys.distinct()).hasSize(1)
        assertThat(retry.attempts.map { it.outcome })
            .containsExactly(CapabilityInvocationCallOutcome.UNKNOWN, CapabilityInvocationCallOutcome.SUCCEEDED)
        assertThat(retry.receipt?.timing?.elapsedNanoseconds).isEqualTo(1_000_000)
        assertThat(retry.status).isEqualTo(CapabilityScenarioStatus.PROOF_ACCEPTED)
    }

    @Test
    fun `terminal receipt replay does not invoke again even for failed actions`() {
        val result = trace(scenario.copy(invocationCount = 3))
        assertThat(result.idempotencyKeys).hasSize(1)
        assertThat(result.attempts).hasSize(1)
        val failed =
            trace(
                scenario.copy(
                    calls = listOf(scenario.calls.single().copy(outcome = CapabilityInvocationCallOutcome.FAILED)),
                    invocationCount = 3,
                ),
            )
        assertThat(failed.status).isEqualTo(CapabilityScenarioStatus.ACTION_FAILED)
        assertThat(failed.proof).isNull()
        assertThat(failed.idempotencyKeys).hasSize(1)
    }

    @Test
    fun `incorrect stale and missing proof never become verified outcomes`() {
        val missing = trace(scenario.copy(proof = emptyList()))
        assertThat(missing.status).isEqualTo(CapabilityScenarioStatus.PROOF_PENDING)
        val wrong = trace(scenario.copy(proof = listOf(scenario.proof.single().copy(value = "LOCKED"))))
        assertThat((wrong.proof as ResolutionOutcomeProofAssessment.Pending).reason)
            .isEqualTo(ResolutionOutcomeProofPendingReason.VALUE_MISMATCH)
        val stale = trace(scenario.copy(proof = listOf(scenario.proof.single().copy(observedAt = f.at.toString()))))
        assertThat((stale.proof as ResolutionOutcomeProofAssessment.Pending).reason)
            .isEqualTo(ResolutionOutcomeProofPendingReason.ELIGIBLE_EVIDENCE_MISSING)
    }

    @Test
    fun `not ready and denied planning cannot invoke and approval-free execution is unsupported`() {
        val sources =
            listOf(
                f.document(history = f.history(emptyList())) to CapabilityScenarioStatus.NOT_READY,
                f.document(
                    policyDocument =
                        f.policy.copy(
                            rules =
                                listOf(
                                    f.policy.rules
                                        .single()
                                        .copy(capability = "identity.other"),
                                ),
                        ),
                )
                    to CapabilityScenarioStatus.POLICY_DENIED,
            )
        sources.forEach { (source, expected) ->
            val result = replay.replay(packageBytes(scenario, source)) as CapabilityScenarioResult.Evaluated
            assertThat(result.trace.status).isEqualTo(expected)
            assertThat(result.trace.idempotencyKeys).isEmpty()
        }
        val source =
            f.document(
                policyDocument =
                    f.policy.copy(
                        rules =
                            listOf(
                                f.policy.rules
                                    .single()
                                    .copy(minimumRisk = StepRisk.LOW, minimumApproval = ApprovalRequirement.NONE),
                            ),
                    ),
            )
        assertThat(replay.replay(packageBytes(scenario, source)))
            .isEqualTo(CapabilityScenarioResult.Unsupported("NO_APPROVAL_EXECUTION_PATH"))
    }

    @Test
    fun `malformed timings ambiguous proof and unused scripted outcomes fail closed`() {
        val invalid =
            listOf(
                scenario.copy(invocationCount = 0),
                scenario.copy(requestLifetimeSeconds = 0),
                scenario.copy(calls = listOf(scenario.calls.single().copy(elapsedNanoseconds = -1))),
                scenario.copy(proof = scenario.proof + scenario.proof),
                scenario.copy(calls = scenario.calls + scenario.calls),
                scenario.copy(
                    calls = listOf(ScenarioCallDocument(CapabilityInvocationCallOutcome.UNKNOWN, 0)),
                    invocationCount = 2,
                ),
            )
        invalid.forEach { script ->
            assertThatThrownBy { trace(script) }.isInstanceOf(InvalidSimulationFixture::class.java)
        }
    }
}
