package org.ergon.controlplane.contracts.adapter.out.persistence

import org.ergon.contracts.domain.ContractPromotionAuthority
import org.ergon.controlplane.contracts.adapter.inbound.document.PromotionDocumentCodec
import org.ergon.controlplane.contracts.application.ContractPromotionIntent
import org.ergon.controlplane.contracts.application.ContractPromotionReceipt
import org.ergon.controlplane.contracts.application.ContractPromotionRepository
import org.ergon.controlplane.contracts.application.RequiredSuiteBinding
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Uses the application transaction; triggers repeat locking, current authority and governing-version admission. */
@Repository
class PostgresContractPromotionRepository(
    private val jdbc: JdbcClient,
    private val codec: PromotionDocumentCodec,
) : ContractPromotionRepository {
    override fun lock(
        tenant: UUID,
        key: String,
        command: UUID,
        operation: String,
    ) {
        jdbc
            .sql("SELECT lock_contract_promotion(:tenant, :key, :command, :operation)")
            .param("tenant", tenant)
            .param("key", key)
            .param("command", command)
            .param("operation", operation)
            .query { _, _ -> Unit }
            .single()
    }

    override fun currentBinding(
        tenant: UUID,
        key: String,
    ): RequiredSuiteBinding? =
        jdbc
            .sql(
                """
                SELECT * FROM contract_required_suite_bindings WHERE tenant_id = :tenant AND contract_key = :key
                ORDER BY version DESC LIMIT 1
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("key", key)
            .query { row, _ -> binding(row) }
            .optional()
            .orElse(null)

    override fun bindingByCommand(
        tenant: UUID,
        command: UUID,
    ): RequiredSuiteBinding? =
        jdbc
            .sql("SELECT * FROM contract_required_suite_bindings WHERE tenant_id = :tenant AND command_id = :command")
            .param("tenant", tenant)
            .param("command", command)
            .query { row, _ -> binding(row) }
            .optional()
            .orElse(null)

    override fun promotion(
        tenant: UUID,
        command: UUID,
    ): ContractPromotionReceipt? =
        jdbc
            .sql("SELECT * FROM contract_promotions WHERE tenant_id = :tenant AND command_id = :command")
            .param("tenant", tenant)
            .param("command", command)
            .query { row, _ -> promotion(row) }
            .optional()
            .orElse(null)

    override fun appendBinding(
        tenant: UUID,
        key: String,
        command: UUID,
        intent: RequiredSuiteIntent,
        authority: ContractPromotionAuthority,
    ): RequiredSuiteBinding =
        promotionWrite {
            jdbc
                .sql(
                    """
                    INSERT INTO contract_required_suite_bindings
                        (tenant_id, contract_key, version, command_id, suite_key, suite_revision, suite_digest,
                         actor_id, authority_id, definition)
                    VALUES (:tenant, :key, :version, :command, :suite, :suiteRevision, :digest, :actor, :authority, :definition)
                    """.trimIndent(),
                ).param("tenant", tenant)
                .param("key", key)
                .param("version", intent.expectedVersion + 1)
                .param("command", command)
                .param("suite", intent.suite.key)
                .param("suiteRevision", intent.suite.revision)
                .param("digest", intent.suite.sha256)
                .param("actor", authority.actorId)
                .param("authority", authority.id)
                .param("definition", codec.encode(intent))
                .update()
            checkNotNull(bindingByCommand(tenant, command))
        }

    override fun appendPromotion(
        tenant: UUID,
        command: UUID,
        intent: ContractPromotionIntent,
        authority: ContractPromotionAuthority,
    ): ContractPromotionReceipt =
        promotionWrite {
            jdbc
                .sql(
                    """
                    INSERT INTO contract_promotions
                        (tenant_id, command_id, contract_key, contract_revision, binding_version, suite_key,
                         suite_revision, suite_digest, actor_id, authority_id, definition)
                    VALUES (:tenant, :command, :key, :revision, :version, :suite, :suiteRevision, :digest, :actor, :authority, :definition)
                    """.trimIndent(),
                ).param("tenant", tenant)
                .param("command", command)
                .param("key", intent.contract.key)
                .param("revision", intent.contract.revision.toInt())
                .param("version", intent.expectedVersion)
                .param("suite", intent.suite.key)
                .param("suiteRevision", intent.suite.revision)
                .param("digest", intent.suite.sha256)
                .param("actor", authority.actorId)
                .param("authority", authority.id)
                .param("definition", codec.encode(intent))
                .update()
            intent.reports.forEach { selected ->
                jdbc
                    .sql(
                        """
                        INSERT INTO contract_promotion_reports (tenant_id, promotion_id, evaluation_id)
                        VALUES (:tenant, :promotion, :report)
                        """.trimIndent(),
                    ).param("tenant", tenant)
                    .param("promotion", command)
                    .param("report", selected.reportId)
                    .update()
            }
            checkNotNull(promotion(tenant, command))
        }

    private fun binding(row: ResultSet): RequiredSuiteBinding {
        val definition = row.getString("definition")
        val intent = codec.binding(definition.toByteArray())
        check(codec.encode(intent) == definition && intent.expectedVersion + 1 == row.getLong("version"))
        check(intent.suite.key == row.getString("suite_key"))
        check(intent.suite.revision == row.getString("suite_revision"))
        check(intent.suite.sha256 == row.getString("suite_digest"))
        return RequiredSuiteBinding(
            row.getString("contract_key"),
            row.getLong("version"),
            row.getObject("command_id", UUID::class.java),
            intent,
            row.getObject("actor_id", UUID::class.java),
            row.getObject("authority_id", UUID::class.java),
            row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    private fun promotion(row: ResultSet): ContractPromotionReceipt {
        val definition = row.getString("definition")
        val intent = codec.promotion(definition.toByteArray())
        check(codec.encode(intent) == definition && intent.expectedVersion == row.getLong("binding_version"))
        check(intent.contract.key == row.getString("contract_key"))
        check(intent.contract.revision.toInt() == row.getInt("contract_revision"))
        check(intent.suite.key == row.getString("suite_key"))
        check(intent.suite.revision == row.getString("suite_revision"))
        check(intent.suite.sha256 == row.getString("suite_digest"))
        return ContractPromotionReceipt(
            row.getObject("command_id", UUID::class.java),
            intent,
            row.getObject("actor_id", UUID::class.java),
            row.getObject("authority_id", UUID::class.java),
            row.getObject("promoted_at", OffsetDateTime::class.java).toInstant(),
        )
    }
}
