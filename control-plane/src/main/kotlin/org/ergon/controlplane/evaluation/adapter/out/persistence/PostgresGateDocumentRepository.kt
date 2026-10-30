package org.ergon.controlplane.evaluation.adapter.out.persistence

import org.ergon.controlplane.evaluation.application.GateDocumentCodec
import org.ergon.controlplane.evaluation.application.GateDocumentConflict
import org.ergon.controlplane.evaluation.application.GateDocumentIdentity
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.GateDocumentRegistration
import org.ergon.controlplane.evaluation.application.GateDocumentRepository
import org.ergon.controlplane.evaluation.application.GateDocumentUnavailable
import org.ergon.controlplane.evaluation.application.PreparedGateDocument
import org.ergon.controlplane.evaluation.application.StoredGateDocument
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

/** Immutable inputs permit validation before this short transaction; SQL retains tenant-safe reference integrity. */
@Repository
class PostgresGateDocumentRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val codec: GateDocumentCodec,
) : GateDocumentRepository {
    override fun register(
        identity: GateDocumentIdentity,
        document: PreparedGateDocument,
        actor: UUID,
    ): GateDocumentRegistration =
        checkNotNull(
            transactions.execute {
                val verified = codec.decode(document.kind, document.definition.toByteArray())
                require(verified.digest == document.digest && verified.definition == document.definition)
                val exists =
                    jdbc
                        .sql(
                            "SELECT EXISTS (SELECT 1 FROM human_actors " +
                                "WHERE tenant_id = :tenant AND actor_id = :actor)",
                        ).param("tenant", identity.tenant)
                        .param("actor", actor)
                        .query(Boolean::class.java)
                        .single()
                if (!exists) throw GateDocumentUnavailable()
                val suite = verified.suite
                val statement =
                    jdbc
                        .sql(
                            """
                            INSERT INTO evaluation_gate_documents
                                (tenant_id, kind, document_key, revision, digest, definition, submitted_by,
                                 policy_kind, policy_key, policy_revision, policy_digest, contract_key, contract_revision)
                            VALUES (:tenant, :kind, :key, :revision, :digest, :definition, :actor,
                                    :policyKind, :policyKey, :policyRevision, :policyDigest, :contractKey, :contractRevision)
                            ON CONFLICT DO NOTHING
                            """.trimIndent(),
                        ).param("tenant", identity.tenant)
                        .param("kind", document.kind.name)
                        .param("key", identity.key)
                        .param("revision", identity.revision)
                        .param("digest", verified.digest)
                        .param("definition", verified.definition)
                        .param("actor", actor)
                        .param("policyKind", suite?.let { "POLICY" }, java.sql.Types.VARCHAR)
                        .param("policyKey", suite?.policy?.key, java.sql.Types.VARCHAR)
                        .param("policyRevision", suite?.policy?.revision, java.sql.Types.VARCHAR)
                        .param("policyDigest", suite?.policy?.sha256, java.sql.Types.VARCHAR)
                        .param("contractKey", suite?.contract?.key, java.sql.Types.VARCHAR)
                        .param("contractRevision", suite?.contract?.revision?.toInt(), java.sql.Types.INTEGER)
                val created = statement.update() == 1
                if (created && suite != null) linkFixtures(identity, suite)
                val stored = checkNotNull(find(document.kind, identity))
                if (stored.definition != verified.definition) throw GateDocumentConflict()
                GateDocumentRegistration(stored, created)
            },
        )

    private fun linkFixtures(
        identity: GateDocumentIdentity,
        suite: org.ergon.evaluation.domain.EvaluationSuite,
    ) {
        suite.scenarios.forEach { scenario ->
            jdbc
                .sql(
                    """
                    INSERT INTO evaluation_suite_fixtures
                        (tenant_id, suite_kind, suite_key, suite_revision, fixture_key, fixture_revision, fixture_digest)
                    VALUES (:tenant, 'SUITE', :key, :revision, :fixture, :fixtureRevision, :digest)
                    """.trimIndent(),
                ).param("tenant", identity.tenant)
                .param("key", identity.key)
                .param("revision", identity.revision)
                .param("fixture", scenario.fixture.key)
                .param("fixtureRevision", scenario.fixture.revision)
                .param("digest", scenario.fixture.sha256)
                .update()
        }
    }

    override fun find(
        kind: GateDocumentKind,
        identity: GateDocumentIdentity,
    ): StoredGateDocument? =
        jdbc
            .sql(
                """
                SELECT digest, definition, submitted_by, recorded_at FROM evaluation_gate_documents
                WHERE tenant_id = :tenant AND kind = :kind AND document_key = :key AND revision = :revision
                """.trimIndent(),
            ).param("tenant", identity.tenant)
            .param("kind", kind.name)
            .param("key", identity.key)
            .param("revision", identity.revision)
            .query { row, _ ->
                val prepared = codec.decode(kind, row.getString("definition").toByteArray())
                check(prepared.digest == row.getString("digest") && prepared.definition == row.getString("definition"))
                StoredGateDocument(
                    identity,
                    kind,
                    prepared.digest,
                    prepared.definition,
                    row.getObject("submitted_by", UUID::class.java),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)
}
