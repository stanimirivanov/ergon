package org.ergon.controlplane.evaluation

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.DocumentaryBundlePinned
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.cases.domain.ResolutionContractRevisionPinned
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.adapter.out.persistence.CaseEventJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioDocumentaryDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioEventDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioPolicyDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioPolicyRule
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioPrefixDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.ScenarioValidityDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationDocumentaryPinDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationManifestDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationMaterialDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationProvenanceDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationProvenanceKind
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationCasePrefix
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.evaluation.domain.SimulationFixture
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal class PinnedScenarioFixtures {
    val mapper = JsonMapper.builder().build()
    val tenant: UUID = UUID.randomUUID()
    val case: UUID = UUID.randomUUID()
    val at: Instant = Instant.parse("2026-10-30T00:00:00Z")
    val bundle: UUID = UUID.randomUUID()
    val bundleDigest = "a".repeat(64)
    val contract =
        """
        schema: ergon.dev/resolution-contract/v1alpha1
        key: restore-access
        revision: 1
        applicability: {fact: account.access.state, equals: LOCKED}
        requiredEvidence: [account.access.state]
        steps:
          - {id: unlock, capability: identity.account.unlock, risk: LOW, approval: NONE}
        outcomeProof: {fact: account.access.state, equals: ACTIVE}
        """.trimIndent()
    val policy =
        ScenarioPolicyDocument(
            PinnedScenarioJsonDecoder.POLICY_SCHEMA,
            "ergon/access/v1",
            listOf(ScenarioPolicyRule("identity.account.unlock", StepRisk.HIGH, ApprovalRequirement.RESOLVER)),
        )

    fun history(states: List<AccountAccessState> = listOf(AccountAccessState.LOCKED)): List<CaseEvent> =
        buildList {
            add(
                CaseOpened(
                    "restore access",
                    UUID.randomUUID(),
                    ObservationOriginType.REQUESTER,
                    "api",
                    null,
                    "locked",
                    at,
                ),
            )
            add(ResolutionContractRevisionPinned("restore-access", 1, at))
            states.forEach { state ->
                val observation = UUID.randomUUID()
                add(
                    ObservationRecorded(
                        observation,
                        ObservationOriginType.CONNECTOR,
                        "identity",
                        "account",
                        "state",
                        at,
                    ),
                )
                add(AccountAccessStateBound(UUID.randomUUID(), observation, "account", state, at))
            }
        }

    fun summary(
        from: Instant? = at.minusSeconds(1),
        until: Instant? = at.plusSeconds(1),
    ) = ScenarioDocumentaryDocument(
        PinnedScenarioJsonDecoder.DOCUMENTARY_SCHEMA,
        bundle,
        bundleDigest,
        at.toString(),
        listOf(ScenarioValidityDocument(from?.toString(), until?.toString())),
    )

    fun documentaryContract(): String =
        contract.replace("v1alpha1", "v1alpha2") +
            "\ndocumentaryEvidence:\n  - {bundleId: '$bundle', sha256: '$bundleDigest'}\n"

    fun document(
        history: List<CaseEvent> = history(),
        contractText: String = contract,
        policyDocument: ScenarioPolicyDocument = policy,
        summary: ScenarioDocumentaryDocument? = null,
    ): SimulationPackageDocument {
        val materials = mutableListOf<SimulationMaterialDocument>()

        fun material(
            key: String,
            content: String,
        ): SimulationArtifact {
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))
            return SimulationArtifact(key, "1", digest).also { materials.add(SimulationMaterialDocument(it, content)) }
        }
        val codec = CaseEventJsonCodec(mapper)
        val entries =
            history.mapIndexed { index, event ->
                val encoded = codec.encode(event)
                ScenarioEventDocument(
                    index + 1L,
                    encoded.eventType,
                    encoded.schemaVersion,
                    at.toString(),
                    mapper.readTree(encoded.payload),
                )
            }
        val prefix = ScenarioPrefixDocument(PinnedScenarioJsonDecoder.PREFIX_SCHEMA, tenant, case, entries)
        val pins =
            if (summary == null) {
                emptyList()
            } else {
                listOf(
                    SimulationDocumentaryPinDocument(
                        material("bundle", mapper.writeValueAsString(summary)),
                        history.size.toLong(),
                        at.toString(),
                    ),
                )
            }
        val manifest =
            SimulationManifestDocument(
                SimulationFixture.VERSION,
                tenant,
                material("restore-access", contractText),
                material("policy", mapper.writeValueAsString(policyDocument)),
                SimulationCasePrefix(
                    case,
                    history.size.toLong(),
                    material("prefix", mapper.writeValueAsString(prefix)),
                ),
                material("evaluator", PinnedScenarioJsonDecoder.EVALUATOR_MATERIAL),
                at.toString(),
                pins,
                emptyList(),
                null,
                material("assertions", "{}"),
                SimulationExpectedOutcome.NOT_READY,
                null,
            )
        return SimulationPackageDocument(
            manifest,
            manifest.toDomain().semanticDigest(),
            materials,
            SimulationProvenanceDocument(SimulationProvenanceKind.SYNTHETIC, "Readiness fixture"),
        )
    }

    fun withBundle(): List<CaseEvent> = history() + DocumentaryBundlePinned(bundle, bundleDigest, at.minusSeconds(30))
}

internal fun PinnedScenarioFixtures.replaceMaterial(
    document: SimulationPackageDocument,
    reference: SimulationArtifact,
    content: String,
): SimulationPackageDocument {
    val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))
    val replacement = reference.copy(sha256 = hash)
    val manifest =
        document.manifest.copy(
            evaluator = if (reference == document.manifest.evaluator) replacement else document.manifest.evaluator,
            casePrefix =
                if (reference == document.manifest.casePrefix.material) {
                    document.manifest.casePrefix.copy(material = replacement)
                } else {
                    document.manifest.casePrefix
                },
        )
    return document.copy(
        manifest = manifest,
        digest = manifest.toDomain().semanticDigest(),
        materials =
            document.materials.map {
                if (it.reference == reference) SimulationMaterialDocument(replacement, content) else it
            },
    )
}
