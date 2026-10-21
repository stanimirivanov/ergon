package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.contracts.domain.FactCondition
import org.ergon.controlplane.contracts.application.RESOLUTION_CONTRACT_DOCUMENT_SCHEMA
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.RunPinnedContract
import org.ergon.controlplane.resolution.application.RunPinnedContractService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Confidential read of normalized declarations; no catalog, authoring YAML, command or executable graph. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/pinned-contract")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunPinnedContractController(
    private val actors: AuthenticatedHumanActorResolver,
    private val contracts: RunPinnedContractService,
) {
    @GetMapping
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserRunPinnedContract> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            contracts.get(tenantId, runId, actor).toBrowserContract(),
        )
    }
}

/** Observation-time access and exact pin attribution; contract recording time is not an execution timestamp. */
data class BrowserRunPinnedContract(
    val runId: UUID,
    val supervisionRevision: Long,
    val observedAt: Instant,
    val pinnedStepId: String,
    val contractRecordedAt: Instant,
    val contract: BrowserPinnedContractDefinition,
)

/** Declared order and safeguards retain immutable v1alpha1 meaning, independently of runtime-effective policy. */
data class BrowserPinnedContractDefinition(
    val schema: String,
    val key: String,
    val revision: Int,
    val applicability: BrowserContractCondition,
    val requiredEvidence: List<String>,
    val steps: List<BrowserContractStep>,
    val outcomeProof: BrowserContractCondition,
)

/** Equality values remain exact/case-sensitive declarations, not assessed evidence or accepted proof. */
data class BrowserContractCondition(
    val fact: String,
    val equals: String,
)

/** A declared capability request, not an executed step, grant, approval decision or causal span. */
data class BrowserContractStep(
    val id: String,
    val capability: String,
    val risk: String,
    val approval: String,
)

private fun FactCondition.toBrowserCondition(): BrowserContractCondition =
    BrowserContractCondition(
        fact = fact.value,
        equals = expectedValue.value,
    )

private fun RunPinnedContract.toBrowserContract(): BrowserRunPinnedContract {
    val definition = revision.contract
    return BrowserRunPinnedContract(
        runId.value,
        supervisionRevision.value,
        observedAt,
        pinnedStepId.value,
        revision.recordedAt,
        BrowserPinnedContractDefinition(
            RESOLUTION_CONTRACT_DOCUMENT_SCHEMA,
            definition.key.value,
            definition.revision.value,
            definition.applicability.toBrowserCondition(),
            definition.requiredEvidence.map { it.value },
            definition.steps.map {
                BrowserContractStep(it.id.value, it.capability.value, it.risk.name, it.approval.name)
            },
            definition.outcomeProof.toBrowserCondition(),
        ),
    )
}
