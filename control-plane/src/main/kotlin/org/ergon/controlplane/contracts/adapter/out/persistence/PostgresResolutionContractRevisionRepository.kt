package org.ergon.controlplane.contracts.adapter.out.persistence

import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.application.ContractRevisionAlreadyExistsException
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for tenant-scoped immutable resolution contract revisions. */
@Repository
class PostgresResolutionContractRevisionRepository(
    private val jdbcClient: JdbcClient,
    private val codec: ResolutionContractJsonCodec,
) : ResolutionContractRevisionRepository {
    override fun publish(
        tenantId: TenantId,
        contract: ResolutionContract,
    ): StoredResolutionContractRevision {
        val encoded = codec.encode(contract)
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_contract_revisions (
                        tenant_id, contract_key, revision, schema_id, definition
                    ) VALUES (
                        :tenantId, :contractKey, :revision, :schemaId, CAST(:definition AS JSONB)
                    )
                    ON CONFLICT (tenant_id, contract_key, revision) DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("contractKey", contract.key.value)
                .param("revision", contract.revision.value)
                .param("schemaId", encoded.schema)
                .param("definition", encoded.definition)
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: throw ContractRevisionAlreadyExistsException(contract.key, contract.revision)
        return StoredResolutionContractRevision(contract, recordedAt.toInstant())
    }

    override fun find(
        tenantId: TenantId,
        key: ResolutionContractKey,
        revision: ResolutionContractRevision,
    ): StoredResolutionContractRevision? =
        jdbcClient
            .sql(
                """
                SELECT
                    contract_key,
                    revision,
                    schema_id,
                    definition::TEXT AS definition,
                    recorded_at
                FROM resolution_contract_revisions
                WHERE tenant_id = :tenantId
                    AND contract_key = :contractKey
                    AND revision = :revision
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("contractKey", key.value)
            .param("revision", revision.value)
            .query(DataClassRowMapper(ResolutionContractRevisionRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredRevision()

    private fun ResolutionContractRevisionRow.toStoredRevision() =
        StoredResolutionContractRevision(
            contract = decodeStoredContract(),
            recordedAt = recordedAt.toInstant(),
        )

    private fun ResolutionContractRevisionRow.decodeStoredContract(): ResolutionContract =
        try {
            codec.decode(contractKey, revision, schemaId, definition)
        } catch (exception: IllegalArgumentException) {
            // Corrupt durable state is an operator failure, never a client validation error.
            throw IllegalStateException("stored resolution contract revision is invalid", exception)
        }
}

private data class ResolutionContractRevisionRow(
    val contractKey: String,
    val revision: Int,
    val schemaId: String,
    val definition: String,
    val recordedAt: OffsetDateTime,
)
