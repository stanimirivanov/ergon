package org.ergon.controlplane.contracts

import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.adapter.inbound.http.ResolutionContractExceptionHandler
import org.ergon.controlplane.contracts.adapter.inbound.http.ResolutionContractRevisionController
import org.ergon.controlplane.contracts.adapter.inbound.http.ResolutionContractValidationController
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.ContractRevisionAlreadyExistsException
import org.ergon.controlplane.contracts.application.PublishedContractBundles
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionService
import org.ergon.controlplane.contracts.application.ResolutionContractValidationService
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class DocumentaryContractHttpTest {
    private val f = DocumentaryContractFixtures
    private val tenant = TenantId(UUID.randomUUID())
    private val records =
        mutableMapOf<
            Triple<TenantId, ResolutionContractKey, ResolutionContractRevision>,
            StoredResolutionContractRevision,
        >()
    private val repository =
        object : ResolutionContractRevisionRepository {
            override fun publish(
                tenantId: TenantId,
                contract: ResolutionContract,
            ): StoredResolutionContractRevision {
                val key = Triple(tenantId, contract.key, contract.revision)
                if (key in records) throw ContractRevisionAlreadyExistsException(contract.key, contract.revision)
                return StoredResolutionContractRevision(contract, Instant.EPOCH).also { records[key] = it }
            }

            override fun find(
                tenantId: TenantId,
                key: ResolutionContractKey,
                revision: ResolutionContractRevision,
            ) = records[Triple(tenantId, key, revision)]
        }
    private val service =
        ResolutionContractRevisionService(
            f.decoder,
            ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
            repository,
            PublishedContractBundles { scoped, reference ->
                scoped == tenant && reference.bundleId == f.bundleId && reference.sha256 == f.digest
            },
        )
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(
                ResolutionContractValidationController(ResolutionContractValidationService(f.decoder)),
                ResolutionContractRevisionController(service),
            ).setControllerAdvice(ResolutionContractExceptionHandler())
            .build()

    @Test
    fun `structural validation and normalized publication identify their actual schema and exact references`() {
        mvc
            .perform(post("/internal/v1/resolution-contracts/validate").contentType(YAML).content(f.legacy))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schema").value("ergon.dev/resolution-contract/v1alpha1"))
            .andExpect(jsonPath("$.documentaryEvidence").doesNotExist())
        mvc
            .perform(post("/internal/v1/resolution-contracts/validate").contentType(YAML).content(f.document))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schema").value("ergon.dev/resolution-contract/v1alpha2"))
        val path = "/internal/v1/tenants/${tenant.value}/resolution-contracts"
        mvc
            .perform(post(path).contentType(YAML).content(f.document))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "$path/restore-workspace-access/revisions/2"))
            .andExpect(jsonPath("$.contract.documentaryEvidence[0].bundleId").value(f.bundleId.toString()))
        mvc
            .perform(get("$path/restore-workspace-access/revisions/2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contract.documentaryEvidence[0].sha256").value(f.digest))
        mvc.perform(post(path).contentType(YAML).content(f.document)).andExpect(status().isConflict)
    }

    @Test
    fun `missing foreign and digest mismatched requirements share a safe publication problem`() {
        for ((scope, document) in listOf(
            TenantId(UUID.randomUUID()) to f.document,
            tenant to f.document(UUID.randomUUID(), f.digest),
            tenant to f.document(f.bundleId, "b".repeat(64)),
        )) {
            val path = "/internal/v1/tenants/${scope.value}/resolution-contracts"
            mvc
                .perform(post(path).contentType(YAML).content(document))
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:unavailable-contract-documentary-evidence"))
                .andExpect(jsonPath("$.violations[0].path").value("$.documentaryEvidence[0]"))
        }
        val mixed = f.document.replace("v1alpha2", "v1alpha1")
        mvc
            .perform(
                post("/internal/v1/resolution-contracts/validate").contentType(YAML).content(mixed),
            ).andExpect(status().isUnprocessableEntity)
        val foreignPath = "/internal/v1/tenants/${UUID.randomUUID()}/resolution-contracts"
        mvc
            .perform(get("$foreignPath/restore-workspace-access/revisions/2"))
            .andExpect(status().isNotFound)
    }

    companion object {
        private val YAML = MediaType.parseMediaType("application/yaml")
    }
}
