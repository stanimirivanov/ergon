package org.ergon.resolution.domain

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable authorization consumption. */
@JvmInline
value class CapabilityAuthorizationConsumptionId(
    val value: UUID,
)

/**
 * Stable connector route configured to provide [capability] inside one tenant boundary.
 *
 * [connector] identifies routing configuration, not a credential or proof of
 * live provider health. Repository lookup supplies the tenant scope.
 */
data class CapabilityRoute(
    val capability: CapabilityName,
    val connector: ConnectorName,
)

/** Validated connector identifier used in durable capability routing. */
@JvmInline
value class ConnectorName private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 100
        const val PATTERN: String = "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$"

        /**
         * Normalizes and validates a stable connector identifier.
         *
         * @throws IllegalArgumentException when the trimmed value is blank, too
         *   long, or not a lowercase dotted or hyphenated identifier.
         */
        fun of(value: String): ConnectorName {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "connector name must not be blank" }
            require(normalized.length <= MAX_LENGTH) { "connector name must not exceed $MAX_LENGTH characters" }
            require(normalized.matches(Regex(PATTERN))) { "connector name must match $PATTERN" }
            return ConnectorName(normalized)
        }
    }
}

/** Grant and configured connector route that must agree before consumption. */
data class CapabilityAuthorizationConsumptionBasis(
    val grant: CapabilityAuthorizationGrant,
    val route: CapabilityRoute,
)

/**
 * Immutable reservation of one grant for later connector invocation.
 *
 * The copied run and capability scope preserves what [authorizationGrantId]
 * authorized and [connector] records the tenant route selected at [consumedAt].
 * This record spends the grant but is not evidence that an action was attempted
 * or succeeded; those facts require a later invocation and receipt.
 */
@ConsistentCopyVisibility
data class CapabilityAuthorizationConsumption private constructor(
    val id: CapabilityAuthorizationConsumptionId,
    val authorizationGrantId: CapabilityAuthorizationGrantId,
    val runId: ResolutionRunId,
    val caseId: CaseId,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val connector: ConnectorName,
    val grantAuthorizedAt: Instant,
    val grantExpiresAt: Instant,
    val consumedAt: Instant,
) {
    companion object {
        /**
         * Spends [CapabilityAuthorizationConsumptionBasis.grant] through its exact capability route.
         *
         * The grant validity interval is half-open, so consumption at its expiry
         * is rejected. Database uniqueness must additionally enforce at-most-once
         * consumption under concurrency.
         *
         * @throws IllegalArgumentException when consumption predates authorization,
         *   occurs at or after expiry, or the route provides another capability.
         */
        fun consume(
            id: CapabilityAuthorizationConsumptionId,
            basis: CapabilityAuthorizationConsumptionBasis,
            consumedAt: Instant,
        ): CapabilityAuthorizationConsumption {
            val (grant, route) = basis
            require(!consumedAt.isBefore(grant.authorizedAt)) {
                "authorization consumption predates its grant"
            }
            require(consumedAt.isBefore(grant.expiresAt)) {
                "authorization grant is expired"
            }
            require(route.capability == grant.capability) {
                "capability route does not provide the authorized capability"
            }
            return CapabilityAuthorizationConsumption(
                id = id,
                authorizationGrantId = grant.id,
                runId = grant.runId,
                caseId = grant.caseId,
                policyRevision = grant.policyRevision,
                stepId = grant.stepId,
                capability = grant.capability,
                connector = route.connector,
                grantAuthorizedAt = grant.authorizedAt,
                grantExpiresAt = grant.expiresAt,
                consumedAt = consumedAt,
            )
        }
    }
}
