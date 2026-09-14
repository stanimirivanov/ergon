package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.CapabilityName
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionBasis
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityRoute
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Tenant capability route paired with the database instant at which it was registered. */
data class StoredCapabilityRoute(
    val route: CapabilityRoute,
    val registeredAt: Instant,
)

/** Tenant-scoped source of configured connector routes. */
fun interface CapabilityRouteRepository {
    /** @return the route configured for [capability], or `null` when the tenant cannot provide it. */
    fun find(
        tenantId: TenantId,
        capability: CapabilityName,
    ): StoredCapabilityRoute?
}

/** Immutable authorization consumption paired with its database recording instant. */
data class StoredCapabilityAuthorizationConsumption(
    val consumption: CapabilityAuthorizationConsumption,
    val recordedAt: Instant,
)

/** Durable tenant-scoped history of single-use authorization consumption. */
interface CapabilityAuthorizationConsumptionRepository {
    /** @return the consumption already recorded for [grantId], or `null` when it remains unspent. */
    fun findIdByGrant(
        tenantId: TenantId,
        grantId: CapabilityAuthorizationGrantId,
    ): CapabilityAuthorizationConsumptionId?

    /**
     * Persists [consumption] once for its grant.
     *
     * The caller must hold the source grant lock through this operation.
     *
     * @throws CapabilityAuthorizationAlreadyConsumedException when another consumption already exists.
     */
    fun create(
        tenantId: TenantId,
        consumption: CapabilityAuthorizationConsumption,
    ): StoredCapabilityAuthorizationConsumption
}

/** Supplies unpredictable consumption identities without coupling the use case to UUID generation. */
fun interface CapabilityAuthorizationConsumptionIdentityGenerator {
    /** @return a fresh identity suitable for immutable consumption history. */
    fun next(): CapabilityAuthorizationConsumptionId
}

/** Signals tenant-scoped grant absence without revealing another tenant's data. */
class CapabilityAuthorizationGrantNotFoundException(
    grantId: UUID,
) : RuntimeException("capability authorization grant $grantId was not found")

/** Signals that a grant reached its exclusive expiry before it could be consumed. */
class CapabilityAuthorizationGrantExpiredException(
    grantId: UUID,
) : RuntimeException("capability authorization grant $grantId is expired")

/** Signals that the tenant has no configured connector route for the authorized capability. */
class TenantCapabilityUnavailableException(
    val capability: String,
) : RuntimeException("tenant capability $capability is unavailable")

/** Identifies the immutable consumption that already spent a grant. */
class CapabilityAuthorizationAlreadyConsumedException(
    val consumptionId: UUID,
) : RuntimeException("authorization grant already has consumption $consumptionId")

/** Persistence sources consulted atomically while consuming one grant. */
data class CapabilityAuthorizationConsumptionRecords(
    val grants: CapabilityAuthorizationGrantRepository,
    val routes: CapabilityRouteRepository,
    val consumptions: CapabilityAuthorizationConsumptionRepository,
)

/** Reserves one current grant through a tenant-configured connector route. */
class CapabilityAuthorizationConsumptionService(
    private val records: CapabilityAuthorizationConsumptionRecords,
    private val identities: CapabilityAuthorizationConsumptionIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Consumes [grantId] exactly once without invoking its selected connector.
     *
     * The grant row remains locked from lookup through insertion. Expiry is
     * evaluated before route lookup so an expired authorization cannot probe
     * tenant capability configuration.
     *
     * @throws CapabilityAuthorizationGrantNotFoundException when [grantId] is absent from [tenantId].
     * @throws CapabilityAuthorizationAlreadyConsumedException when the grant was already spent.
     * @throws CapabilityAuthorizationGrantExpiredException when the grant is no longer current.
     * @throws TenantCapabilityUnavailableException when no matching tenant route exists.
     */
    fun consume(
        tenantId: UUID,
        grantId: UUID,
    ): StoredCapabilityAuthorizationConsumption =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedGrantId = CapabilityAuthorizationGrantId(grantId)
            val grant =
                records.grants.lockForConsumption(scopedTenantId, scopedGrantId)?.grant
                    ?: throw CapabilityAuthorizationGrantNotFoundException(grantId)
            records.consumptions.findIdByGrant(scopedTenantId, scopedGrantId)?.let {
                throw CapabilityAuthorizationAlreadyConsumedException(it.value)
            }
            val now = clock.instant()
            if (!now.isBefore(grant.expiresAt)) {
                throw CapabilityAuthorizationGrantExpiredException(grantId)
            }
            val route =
                records.routes.find(scopedTenantId, grant.capability)?.route
                    ?: throw TenantCapabilityUnavailableException(grant.capability.value)
            val consumption =
                try {
                    CapabilityAuthorizationConsumption.consume(
                        id = identities.next(),
                        basis = CapabilityAuthorizationConsumptionBasis(grant, route),
                        consumedAt = now,
                    )
                } catch (exception: IllegalArgumentException) {
                    // Both inputs are durable and constrained, so mismatch means corruption.
                    throw IllegalStateException("stored authorization consumption sources are inconsistent", exception)
                }
            records.consumptions.create(scopedTenantId, consumption)
        }
}
