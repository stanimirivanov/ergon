package org.ergon.controlplane.contracts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.adapter.inbound.http.ResolutionContractValidationController
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA
import org.ergon.controlplane.contracts.application.InvalidContractDocumentaryEvidenceException
import org.ergon.controlplane.contracts.application.InvalidResolutionContractDocumentException
import org.ergon.controlplane.contracts.application.PublishedContractBundles
import org.ergon.controlplane.contracts.application.RESOLUTION_CONTRACT_DOCUMENT_SCHEMA
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionService
import org.ergon.controlplane.contracts.application.ResolutionContractValidationService
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class DocumentaryContractTest {
    private val f = DocumentaryContractFixtures
    private val mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val codec = ResolutionContractJsonCodec(mapper)

    @Test
    fun `strict schema dispatch preserves examples and rejects mixed unsupported and malformed declarations`() {
        assertThat(f.decoder.decode(f.legacy).documentaryEvidence).isEmpty()
        val contract = f.decoder.decode(f.document)
        assertThat(contract.documentaryEvidence.single().bundleId).isEqualTo(f.bundleId)
        assertThat(contract.documentaryEvidence.single().sha256).isEqualTo(f.digest)
        val invalid =
            listOf(
                f.document.replace("v1alpha2", "v1alpha1"),
                f.document.replace("v1alpha2", "v9"),
                f.document.substringBefore("documentaryEvidence:"),
                f.document.substringBefore("documentaryEvidence:") + "documentaryEvidence: []",
                f.document.replace("bundleId:", "unexpected:"),
                f.document.replace(f.bundleId.toString(), "1-1-1-1-1"),
                f.document.replace(f.digest, "invalid"),
                f.document.replace("sha256:", "sha256: null\n    extra:"),
                f.document + f.document.substringAfter("documentaryEvidence:"),
                f.document.replace("    sha256:", "    bundleId: \"${f.bundleId}\"\n    sha256:"),
            )
        for (document in invalid) {
            assertThatThrownBy { f.decoder.decode(document) }
                .isInstanceOf(InvalidResolutionContractDocumentException::class.java)
        }
    }

    @Test
    fun `strict persisted version dispatch rejects misplaced missing unknown and corrupt requirements`() {
        for (document in listOf(f.legacy, f.document)) {
            val contract = f.decoder.decode(document)
            val encoded = codec.encode(contract)
            val decoded = codec.decode(contract.key.value, contract.revision.value, encoded.schema, encoded.definition)
            assertThat(codec.encode(decoded)).isEqualTo(encoded)
            assertThat(decoded.documentaryEvidence).isEqualTo(contract.documentaryEvidence)
        }
        val legacy = codec.encode(f.decoder.decode(f.legacy))
        val documentary = codec.encode(f.decoder.decode(f.document))
        assertThat(legacy.schema).isEqualTo(RESOLUTION_CONTRACT_DOCUMENT_SCHEMA)
        assertThat(documentary.schema).isEqualTo(DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA)
        for ((schema, definition) in listOf(
            RESOLUTION_CONTRACT_DOCUMENT_SCHEMA to documentary.definition,
            DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA to legacy.definition,
            "unknown" to legacy.definition,
            legacy.schema to (legacy.definition.dropLast(1) + ",\"documentaryEvidence\":null}"),
            documentary.schema to documentary.definition.replace(f.digest, "wrong"),
            documentary.schema to documentary.definition.replace("\"${f.digest}\"", "1".repeat(64)),
            documentary.schema to documentary.definition.replace(f.bundleId.toString(), "1-1-1-1-1"),
        )) {
            assertThatThrownBy { codec.decode("restore-workspace-access", 1, schema, definition) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `normalized validation reports the actual schema and preserves the legacy JSON shape`() {
        val controller = ResolutionContractValidationController(ResolutionContractValidationService(f.decoder))
        val legacy = mapper.readTree(mapper.writeValueAsString(controller.validate(f.legacy)))
        assertThat(legacy.has("documentaryEvidence")).isFalse()
        assertThat(legacy.path("schema").stringValue()).isEqualTo(RESOLUTION_CONTRACT_DOCUMENT_SCHEMA)
        val documentary = mapper.readTree(mapper.writeValueAsString(controller.validate(f.document)))
        assertThat(documentary.path("schema").stringValue()).isEqualTo(DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA)
        assertThat(
            documentary
                .path("documentaryEvidence")
                .get(0)
                .path("sha256")
                .stringValue(),
        ).isEqualTo(f.digest)
    }

    @Test
    fun `publication requires tenant exact bundle references while legacy publication needs no documentary lookup`() {
        val tenant = TenantId(UUID.randomUUID())
        var stored: ResolutionContract? = null
        val repository =
            object : ResolutionContractRevisionRepository {
                override fun publish(
                    tenantId: TenantId,
                    contract: ResolutionContract,
                ): StoredResolutionContractRevision {
                    stored = contract
                    return StoredResolutionContractRevision(contract, Instant.EPOCH)
                }

                override fun find(
                    tenantId: TenantId,
                    key: ResolutionContractKey,
                    revision: ResolutionContractRevision,
                ) = null
            }
        var lookups = 0
        val bundles =
            PublishedContractBundles { scoped, requirement ->
                lookups++
                scoped == tenant && requirement.bundleId == f.bundleId && requirement.sha256 == f.digest
            }
        val service =
            ResolutionContractRevisionService(
                f.decoder,
                ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
                repository,
                bundles,
            )
        service.publish(tenant, f.legacy)
        assertThat(lookups).isZero()
        service.publish(tenant, f.document)
        assertThat(stored?.documentaryEvidence).hasSize(1)
        val published = stored
        for ((scope, document) in listOf(
            TenantId(UUID.randomUUID()) to f.document,
            tenant to f.document(UUID.randomUUID(), f.digest),
            tenant to f.document(f.bundleId, "b".repeat(64)),
        )) {
            assertThatThrownBy { service.publish(scope, document) }
                .isInstanceOfSatisfying(InvalidContractDocumentaryEvidenceException::class.java) {
                    assertThat(it.violations.single().path).isEqualTo("$.documentaryEvidence[0]")
                }
            assertThat(stored).isSameAs(published)
        }
    }
}
