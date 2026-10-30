package org.ergon.controlplane.contracts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.contracts.adapter.out.evidence.PublishedEvidenceBundleReferences
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.ContractRevisionAlreadyExistsException
import org.ergon.controlplane.contracts.application.ContractRevisionNotFoundException
import org.ergon.controlplane.contracts.application.InvalidContractDocumentaryEvidenceException
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionService
import org.ergon.controlplane.evidence.EvidenceBundleDatabase
import org.ergon.identity.domain.TenantId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class DocumentaryContractIntegrationTest {
    @Test
    fun `publishes exact historical references with complete immutable tenant safe links`() {
        val d = Database()
        val published = d.service.publish(d.tenant, d.document)
        val contract = published.contract
        assertThat(
            d.service
                .get(d.tenant, contract.key, contract.revision)
                .contract.documentaryEvidence,
        ).isEqualTo(contract.documentaryEvidence)
        assertThat(d.countLinks()).isEqualTo(1)
        assertThatThrownBy { d.service.publish(d.tenant, d.document) }
            .isInstanceOf(ContractRevisionAlreadyExistsException::class.java)
        assertThatThrownBy { d.service.get(TenantId(UUID.randomUUID()), contract.key, contract.revision) }
            .isInstanceOf(ContractRevisionNotFoundException::class.java)
        for (table in listOf("resolution_contract_revisions", "resolution_contract_documentary_evidence")) {
            assertThatThrownBy {
                d.b.db.jdbc
                    .sql("DELETE FROM $table")
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                d.b.db.jdbc
                    .sql("UPDATE $table SET tenant_id = tenant_id")
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
        // Later accepted alternatives do not change the referenced historical publication.
        d.b.db.accept(d.b.db.add(120))
        assertThat(
            d.b.repository
                .find(d.tenant, d.bundle.command.id)
                ?.sha256,
        ).isEqualTo(d.bundle.sha256)
        assertThat(
            d.service
                .get(d.tenant, contract.key, contract.revision)
                .contract.documentaryEvidence,
        ).isEqualTo(contract.documentaryEvidence)
    }

    @Test
    fun `missing foreign and mismatched references fail service checks and atomic database admission`() {
        val d = Database()
        val f = DocumentaryContractFixtures
        val invalid =
            listOf(
                TenantId(UUID.randomUUID()) to d.document,
                d.tenant to f.document(UUID.randomUUID(), d.bundle.sha256),
                d.tenant to f.document(d.bundle.command.id, "0".repeat(64)),
            )
        for ((tenant, document) in invalid) {
            assertThatThrownBy { d.service.publish(tenant, document) }
                .isInstanceOf(InvalidContractDocumentaryEvidenceException::class.java)
            val contract = f.decoder.decode(document)
            // Bypass application validation: the composite FK still rejects and rolls back the parent INSERT.
            assertThatThrownBy { d.repository.publish(tenant, contract) }.isInstanceOf(DataAccessException::class.java)
            assertThat(d.repository.find(tenant, contract.key, contract.revision)).isNull()
        }
        assertThat(d.countLinks()).isZero()
        d.service.publish(d.tenant, d.document)
        assertThat(d.countLinks()).isEqualTo(1)
        assertThatThrownBy {
            d.b.db.jdbc
                .sql(
                    """
                    INSERT INTO resolution_contract_documentary_evidence
                      (tenant_id, contract_key, contract_revision, bundle_id, manifest_sha256)
                    VALUES (:tenant, 'restore-workspace-access', 2, :bundle, :digest)
                    """.trimIndent(),
                ).param("tenant", d.tenant.value)
                .param("bundle", UUID.randomUUID())
                .param("digest", "a".repeat(64))
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(d.countLinks()).isEqualTo(1)
    }

    @Test
    fun `unattached documentary runs and mixed schema direct definitions fail closed`() {
        val d = Database()
        d.service.publish(d.tenant, d.document)
        assertThatThrownBy {
            d.b.db.jdbc
                .sql(
                    """
                    INSERT INTO resolution_runs (tenant_id, contract_key, contract_revision)
                    VALUES (:tenant, 'restore-workspace-access', 2)
                    """.trimIndent(),
                ).param("tenant", d.tenant.value)
                .update()
        }.isInstanceOfSatisfying(DataAccessException::class.java) { error ->
            val causes = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>()
            assertThat(causes.first().sqlState)
                .isEqualTo("P7801")
        }
        for ((schema, definition) in listOf("v1alpha1" to "{\"documentaryEvidence\":[]}", "v1alpha2" to "{}")) {
            assertThatThrownBy {
                d.b.db.jdbc
                    .sql(
                        """
                        INSERT INTO resolution_contract_revisions
                            (tenant_id, contract_key, revision, schema_id, definition)
                        VALUES (:tenant, 'invalid', 1, :schema, CAST(:definition AS JSONB))
                        """.trimIndent(),
                    ).param("tenant", d.tenant.value)
                    .param("schema", "ergon.dev/resolution-contract/$schema")
                    .param("definition", definition)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `upgrade preserves old definitions and existing bundles before publishing the new schema`() {
        val d = Database("20261029220000")
        val legacy = d.service.publish(d.tenant, DocumentaryContractFixtures.legacy)
        val before =
            d.b.db.jdbc
                .sql("SELECT definition::TEXT FROM resolution_contract_revisions")
                .query(String::class.java)
                .single()
        val migrations = migrations(d.b.db.schema)
        migrations.migrate()
        migrations.validate()
        assertThat(
            d.b.db.jdbc
                .sql("SELECT definition::TEXT FROM resolution_contract_revisions")
                .query(String::class.java)
                .single(),
        ).isEqualTo(before)
        assertThat(
            d.service
                .get(d.tenant, legacy.contract.key, legacy.contract.revision)
                .contract.documentaryEvidence,
        ).isEmpty()
        assertThat(
            d.b.repository
                .find(d.tenant, d.bundle.command.id)
                ?.sha256,
        ).isEqualTo(d.bundle.sha256)
        d.service.publish(d.tenant, d.document)
        assertThat(d.countLinks()).isEqualTo(1)
    }

    @Test
    fun `complete migration chain validates on an independent empty schema`() {
        val schema = "documentary_" + UUID.randomUUID().toString().replace("-", "")
        val migrations = migrations(schema)
        migrations.migrate()
        migrations.validate()
        assertThat(
            migrations
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030120000")
    }

    private fun migrations(schema: String): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema)
            .defaultSchema(schema)
            .load()

    private class Database(
        target: String = "latest",
    ) {
        val b = EvidenceBundleDatabase(postgres, migrationTarget = target)
        val tenant = b.f.source.tenant
        val bundle = b.repository.publish(b.db.admission, b.command, b.content).stored
        val repository =
            PostgresResolutionContractRevisionRepository(
                b.db.jdbc,
                ResolutionContractJsonCodec(JsonMapper.builder().build()),
            )
        val service =
            ResolutionContractRevisionService(
                DocumentaryContractFixtures.decoder,
                ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
                repository,
                PublishedEvidenceBundleReferences(b.repository),
            )
        val document = DocumentaryContractFixtures.document(bundle.command.id, bundle.sha256)

        fun countLinks(): Int =
            b.db.jdbc
                .sql("SELECT COUNT(*) FROM resolution_contract_documentary_evidence")
                .query(Int::class.java)
                .single()
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
