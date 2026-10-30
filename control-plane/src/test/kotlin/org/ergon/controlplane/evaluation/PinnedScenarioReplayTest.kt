package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.ResolutionContractRevisionPinned
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.contracts.adapter.inbound.document.SnakeYamlResolutionContractDocumentDecoder
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationFixtureJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.PinnedScenarioReplay
import org.ergon.controlplane.evaluation.application.PinnedScenarioReplayResult
import org.ergon.controlplane.evaluation.application.UnsupportedScenarioReason
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.resolution.domain.DocumentaryUnsuitability
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import java.util.UUID

class PinnedScenarioReplayTest {
    private val f = PinnedScenarioFixtures()
    private val decoder =
        PinnedScenarioJsonDecoder(
            SimulationFixtureJsonCodec(f.mapper),
            SnakeYamlResolutionContractDocumentDecoder(),
            ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
            f.mapper,
        )
    private val replay = PinnedScenarioReplay(decoder)

    private fun run(document: SimulationPackageDocument) = replay.replay(f.mapper.writeValueAsBytes(document))

    private fun evaluated(document: SimulationPackageDocument) = run(document) as PinnedScenarioReplayResult.Evaluated

    private fun readiness(document: SimulationPackageDocument): ResolutionReadiness {
        val result = evaluated(document).plan.readiness as CaseReadiness.Evaluated
        return result.readiness
    }

    @Test
    fun `repeated replay is identical and policy strengthens the first step without executing expectations`() {
        val document = f.document()
        val first = evaluated(document)
        assertThat(evaluated(document)).usingRecursiveComparison().isEqualTo(first)
        assertThat(readiness(document)).isInstanceOf(ResolutionReadiness.Ready::class.java)
        assertThat(first.plan.nextStep?.policyDecision)
            .isEqualTo(StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.RESOLVER))
        // The deliberately mismatching NOT_READY expectation is not assessed by this planning slice.
        assertThat(first.fixtureDigest).isEqualTo(document.digest)
        assertThat(first.plan.readiness.caseStreamVersion).isEqualTo(document.manifest.casePrefix.streamVersion)
    }

    @Test
    fun `missing evidence nonapplicability and latest bound value use production readiness`() {
        assertThat(readiness(f.document(history = f.history(emptyList()))))
            .isInstanceOf(ResolutionReadiness.MissingEvidence::class.java)
        assertThat(readiness(f.document(history = f.history(listOf(AccountAccessState.ACTIVE)))))
            .isInstanceOf(ResolutionReadiness.NotApplicable::class.java)
        val conflicting = f.history(listOf(AccountAccessState.ACTIVE, AccountAccessState.LOCKED))
        assertThat(readiness(f.document(history = conflicting)))
            .isInstanceOf(ResolutionReadiness.Ready::class.java)
        val waiting = f.document(history = f.history().filterNot { it is ResolutionContractRevisionPinned })
        assertThat(evaluated(waiting).plan.readiness).isInstanceOf(CaseReadiness.WaitingForContract::class.java)
        assertThat(evaluated(waiting).plan.nextStep).isNull()
    }

    @Test
    fun `unknown capability in policy denies instead of granting default requirements`() {
        val policy =
            f.policy.copy(
                rules =
                    listOf(
                        f.policy.rules
                            .single()
                            .copy(capability = "identity.other"),
                    ),
            )
        assertThat(evaluated(f.document(policyDocument = policy)).plan.nextStep?.policyDecision)
            .isInstanceOf(StepPolicyDecision.Denied::class.java)
    }

    @Test
    fun `documentary validity uses recorded attachment not occurrence or later evaluation time`() {
        val document = f.document(f.withBundle(), f.documentaryContract(), summary = f.summary())
        assertThat(readiness(document)).isInstanceOf(ResolutionReadiness.Ready::class.java)
        val later = document.manifest.copy(evaluationAt = f.at.plusSeconds(100).toString())
        assertThat(readiness(document.copy(manifest = later, digest = later.toDomain().semanticDigest())))
            .isEqualTo(readiness(document))
        val invalid = f.document(f.withBundle(), f.documentaryContract(), summary = f.summary(until = f.at))
        val result = readiness(invalid) as ResolutionReadiness.DocumentaryEvidenceUnavailable
        assertThat(result.assessments.single().reason).isEqualTo(DocumentaryUnsuitability.OUTSIDE_VALIDITY)
        val unknown = f.document(f.withBundle(), f.documentaryContract(), summary = f.summary(null, null))
        val unknownResult = readiness(unknown) as ResolutionReadiness.DocumentaryEvidenceUnavailable
        assertThat(unknownResult.assessments.single().reason)
            .isEqualTo(DocumentaryUnsuitability.UNKNOWN_VALIDITY)
    }

    @Test
    fun `missing documentary evidence precedes missing operational evidence`() {
        val document = f.document(history = f.history(emptyList()), contractText = f.documentaryContract())
        val result = readiness(document) as ResolutionReadiness.DocumentaryEvidenceUnavailable
        assertThat(result.missing).isTrue()
        assertThat(evaluated(document).plan.nextStep).isNull()
    }

    @Test
    fun `rejects mismatched tenant identity prefix contract and attachment recording time`() {
        val document = f.document()
        val variants =
            listOf(
                document.manifest.copy(tenantId = UUID.randomUUID()),
                document.manifest.copy(casePrefix = document.manifest.casePrefix.copy(streamVersion = 3)),
                document.manifest.copy(casePrefix = document.manifest.casePrefix.copy(caseId = UUID.randomUUID())),
            )
        variants.forEach { manifest ->
            assertThatThrownBy {
                run(document.copy(manifest = manifest, digest = manifest.toDomain().semanticDigest()))
            }.isInstanceOf(InvalidSimulationFixture::class.java)
        }
        val pins = f.document(f.withBundle(), f.documentaryContract(), summary = f.summary())
        val manifest =
            pins.manifest.copy(
                documentaryPins =
                    listOf(
                        pins.manifest.documentaryPins
                            .single()
                            .copy(attachedAt = f.at.minusSeconds(1).toString()),
                    ),
            )
        assertThatThrownBy { run(pins.copy(manifest = manifest, digest = manifest.toDomain().semanticDigest())) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        assertThatThrownBy { run(f.document(contractText = f.contract.replace("revision: 1", "revision: 2"))) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        assertThatThrownBy { run(document.copy(digest = "0".repeat(64))) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
    }

    @Test
    fun `unsupported material versions and contract vocabulary are explicit`() {
        assertThat(run(f.document(policyDocument = f.policy.copy(schemaVersion = "future"))))
            .isEqualTo(PinnedScenarioReplayResult.Unsupported(UnsupportedScenarioReason.MATERIAL_VERSION))
        assertThat(run(f.document(contractText = f.contract.replace("identity.account.unlock", "identity.other"))))
            .isEqualTo(PinnedScenarioReplayResult.Unsupported(UnsupportedScenarioReason.CONTRACT_VOCABULARY))
    }

    @Test
    fun `unsupported evaluator scripts and event schemas never yield a planning observation`() {
        val document = f.document()
        assertThat(run(f.replaceMaterial(document, document.manifest.evaluator, "future evaluator")))
            .isEqualTo(PinnedScenarioReplayResult.Unsupported(UnsupportedScenarioReason.EVALUATOR))
        val manifest =
            document.manifest.copy(
                scripts =
                    listOf(
                        org.ergon.evaluation.domain.SimulationScript(
                            org.ergon.evaluation.domain.SimulationScriptKind.CONNECTOR,
                            document.manifest.assertions,
                        ),
                    ),
            )
        assertThat(run(document.copy(manifest = manifest, digest = manifest.toDomain().semanticDigest())))
            .isEqualTo(PinnedScenarioReplayResult.Unsupported(UnsupportedScenarioReason.EXECUTION_SCRIPT))
        val ref = document.manifest.casePrefix.material
        val original = document.materials.single { it.reference == ref }.content
        val prefix =
            f.mapper.readValue(
                original,
                org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioPrefixDocument::class.java,
            )
        val future = prefix.copy(events = prefix.events.map { it.copy(schemaVersion = 2) })
        assertThat(run(f.replaceMaterial(document, ref, f.mapper.writeValueAsString(future))))
            .isEqualTo(PinnedScenarioReplayResult.Unsupported(UnsupportedScenarioReason.EVENT))
    }

    @Test
    fun `gapped future and malformed prefixes are rejected rather than repaired`() {
        val document = f.document()
        val ref = document.manifest.casePrefix.material
        val prefix =
            f.mapper.readValue(
                document.materials.single { it.reference == ref }.content,
                org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioPrefixDocument::class.java,
            )
        val invalid =
            listOf(
                prefix.copy(events = prefix.events.map { it.copy(version = it.version + 1) }),
                prefix.copy(events = prefix.events + prefix.events.last()),
                prefix.copy(events = prefix.events.map { it.copy(recordedAt = f.at.plusSeconds(1).toString()) }),
            )
        invalid.forEach {
            assertThatThrownBy { run(f.replaceMaterial(document, ref, f.mapper.writeValueAsString(it))) }
                .isInstanceOf(InvalidSimulationFixture::class.java)
        }
        assertThatThrownBy { run(f.replaceMaterial(document, ref, "null")) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
    }
}
