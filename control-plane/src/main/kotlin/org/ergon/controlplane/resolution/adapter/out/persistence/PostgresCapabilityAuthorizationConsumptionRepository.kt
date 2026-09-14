package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.contracts.domain.CapabilityName
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationAlreadyConsumedException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionRepository
import org.ergon.controlplane.resolution.application.CapabilityRouteRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityAuthorizationConsumption
import org.ergon.controlplane.resolution.application.StoredCapabilityRoute
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityRoute
import org.ergon.resolution.domain.ConnectorName
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for configured tenant routes and immutable grant consumptions. */
@Repository
class PostgresCapabilityAuthorizationConsumptionRepository(
    private val jdbcClient: JdbcClient,
) : CapabilityRouteRepository,
    CapabilityAuthorizationConsumptionRepository {
    override fun find(
        tenantId: TenantId,
        capability: CapabilityName,
    ): StoredCapabilityRoute? =
        jdbcClient
            .sql(
                """
                SELECT capability, connector, registered_at
                FROM tenant_capability_routes
                WHERE tenant_id = :tenantId AND capability = :capability
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("capability", capability.value)
            .query(DataClassRowMapper(CapabilityRouteRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredRoute()

    override fun findIdByGrant(
        tenantId: TenantId,
        grantId: CapabilityAuthorizationGrantId,
    ): CapabilityAuthorizationConsumptionId? =
        jdbcClient
            .sql(
                """
                SELECT authorization_consumption_id
                FROM capability_authorization_consumptions
                WHERE tenant_id = :tenantId AND authorization_grant_id = :grantId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("grantId", grantId.value)
            .query(UUID::class.java)
            .optional()
            .getOrNull()
            ?.let(::CapabilityAuthorizationConsumptionId)

    override fun create(
        tenantId: TenantId,
        consumption: CapabilityAuthorizationConsumption,
    ): StoredCapabilityAuthorizationConsumption {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO capability_authorization_consumptions (
                        tenant_id, authorization_consumption_id, authorization_grant_id,
                        run_id, case_id, policy_revision, step_id, capability, connector,
                        grant_authorized_at, grant_expires_at, consumed_at
                    ) VALUES (
                        :tenantId, :consumptionId, :grantId,
                        :runId, :caseId, :policyRevision, :stepId, :capability, :connector,
                        :grantAuthorizedAt, :grantExpiresAt, :consumedAt
                    )
                    ON CONFLICT (tenant_id, authorization_grant_id) DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("consumptionId", consumption.id.value)
                .param("grantId", consumption.authorizationGrantId.value)
                .param("runId", consumption.runId.value)
                .param("caseId", consumption.caseId.value)
                .param("policyRevision", consumption.policyRevision.value)
                .param("stepId", consumption.stepId.value)
                .param("capability", consumption.capability.value)
                .param("connector", consumption.connector.value)
                .param("grantAuthorizedAt", consumption.grantAuthorizedAt.atOffset(ZoneOffset.UTC))
                .param("grantExpiresAt", consumption.grantExpiresAt.atOffset(ZoneOffset.UTC))
                .param("consumedAt", consumption.consumedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: throw CapabilityAuthorizationAlreadyConsumedException(
                    requireNotNull(findIdByGrant(tenantId, consumption.authorizationGrantId)).value,
                )
        return StoredCapabilityAuthorizationConsumption(consumption, recordedAt.toInstant())
    }
}

private fun CapabilityRouteRow.toStoredRoute(): StoredCapabilityRoute =
    try {
        StoredCapabilityRoute(
            route = CapabilityRoute(CapabilityName.of(capability), ConnectorName.of(connector)),
            registeredAt = registeredAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        // Invalid persisted configuration is an operator failure, not unavailable capability.
        throw IllegalStateException("stored tenant capability route is invalid", exception)
    }

private data class CapabilityRouteRow(
    val capability: String,
    val connector: String,
    val registeredAt: OffsetDateTime,
)
