package org.ergon.controlplane.cases.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.ergon.cases.domain.AccountAccessState
import org.ergon.controlplane.cases.application.AccountAccessFact
import org.ergon.controlplane.cases.application.BindAccountAccessStateCommand
import org.ergon.controlplane.cases.application.CaseAccountAccessFacts
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Public read adapter for account-access facts in a tenant-scoped case. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states")
class CaseAccountAccessFactController(
    private val queryService: CaseQueryService,
) {
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
    ): CaseAccountAccessFactsResponse = queryService.accountAccessFacts(tenantId, caseId).toResponse()
}

/**
 * Internal semantic-binding adapter. The caller proposes a typed state but
 * cannot choose the account to which the supporting observation is attributed.
 */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states")
class AccountAccessStateBindingController(
    private val commandService: CaseCommandService,
) {
    @PostMapping
    fun bind(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody request: BindAccountAccessStateRequest,
    ): ResponseEntity<CaseWriteResponse> {
        val result =
            commandService.bindAccountAccessState(
                BindAccountAccessStateCommand(
                    tenantId = tenantId,
                    caseId = caseId,
                    expectedVersion = parseVersion(ifMatch),
                    observationId = request.observationId,
                    state = AccountAccessState.valueOf(request.state),
                ),
            )
        return ResponseEntity.ok().eTag(result.etag()).body(result.toResponse())
    }
}

data class BindAccountAccessStateRequest(
    val observationId: UUID,
    @field:Pattern(regexp = "ACTIVE|LOCKED") val state: String,
)

data class CaseAccountAccessFactsResponse(
    val caseId: UUID,
    val streamVersion: Long,
    val facts: List<AccountAccessFactResponse>,
)

/** The evidence reference and two timestamps prevent a fact from losing provenance. */
data class AccountAccessFactResponse(
    val factId: UUID,
    val streamVersion: Long,
    val observationId: UUID,
    val accountReference: String,
    val state: String,
    val boundAt: Instant,
    val recordedAt: Instant,
)

private fun CaseAccountAccessFacts.toResponse() =
    CaseAccountAccessFactsResponse(
        caseId = caseId,
        streamVersion = streamVersion,
        facts = facts.map(AccountAccessFact::toResponse),
    )

private fun AccountAccessFact.toResponse() =
    AccountAccessFactResponse(
        factId = factId,
        streamVersion = streamVersion,
        observationId = observationId,
        accountReference = accountReference,
        state = state.name,
        boundAt = boundAt,
        recordedAt = recordedAt,
    )
