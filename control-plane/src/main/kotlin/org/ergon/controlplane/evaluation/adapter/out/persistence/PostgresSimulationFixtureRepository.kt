package org.ergon.controlplane.evaluation.adapter.out.persistence

import org.ergon.controlplane.evaluation.application.PreparedSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureConflict
import org.ergon.controlplane.evaluation.application.SimulationFixtureDecoder
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureRegistration
import org.ergon.controlplane.evaluation.application.SimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.SimulationFixtureUnavailable
import org.ergon.controlplane.evaluation.application.StoredSimulationFixture
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

/** READ COMMITTED conflict wait followed by a fresh read preserves the winner's complete package and attribution. */
@Repository
class PostgresSimulationFixtureRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val decoder: SimulationFixtureDecoder,
) : SimulationFixtureRepository {
    override fun register(
        identity: SimulationFixtureIdentity,
        prepared: PreparedSimulationFixture,
        actor: UUID,
    ): SimulationFixtureRegistration =
        checkNotNull(
            transactions.execute {
                require(prepared.fixture.tenantId == identity.tenantId)
                val actorExists =
                    jdbc
                        .sql(
                            "SELECT EXISTS (SELECT 1 FROM human_actors " +
                                "WHERE tenant_id = :tenant AND actor_id = :actor)",
                        ).param("tenant", identity.tenantId)
                        .param("actor", actor)
                        .query(Boolean::class.java)
                        .single()
                if (!actorExists) throw SimulationFixtureUnavailable()
                val created =
                    identity(
                        """
                        INSERT INTO simulation_fixture_revisions
                            (tenant_id, fixture_key, revision, digest, canonical, definition, submitted_by)
                        VALUES (:tenant, :key, :revision, :digest, :canonical, :definition, :actor)
                        ON CONFLICT (tenant_id, fixture_key, revision) DO NOTHING
                        """.trimIndent(),
                        identity,
                    ).param("digest", prepared.fixture.semanticDigest())
                        .param("canonical", prepared.fixture.canonicalBytes())
                        .param("definition", prepared.definition)
                        .param("actor", actor)
                        .update() == 1
                val stored = checkNotNull(find(identity))
                if (stored.definition != prepared.definition) throw SimulationFixtureConflict()
                SimulationFixtureRegistration(stored, created)
            },
        )

    override fun find(identity: SimulationFixtureIdentity): StoredSimulationFixture? =
        identity(
            """
            SELECT digest, canonical, definition, submitted_by, recorded_at
            FROM simulation_fixture_revisions
            WHERE tenant_id = :tenant AND fixture_key = :key AND revision = :revision
            """.trimIndent(),
            identity,
        ).query { row, _ ->
            val prepared = decoder.decode(row.getString("definition").toByteArray(Charsets.UTF_8))
            check(prepared.fixture.tenantId == identity.tenantId)
            check(prepared.fixture.semanticDigest() == row.getString("digest"))
            check(prepared.fixture.canonicalBytes().contentEquals(row.getBytes("canonical")))
            StoredSimulationFixture(
                identity,
                row.getString("digest"),
                prepared.definition,
                row.getObject("submitted_by", UUID::class.java),
                row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
            )
        }.optional()
            .orElse(null)

    private fun identity(
        sql: String,
        identity: SimulationFixtureIdentity,
    ): JdbcClient.StatementSpec =
        jdbc
            .sql(sql)
            .param("tenant", identity.tenantId)
            .param("key", identity.key)
            .param("revision", identity.revision)
}
