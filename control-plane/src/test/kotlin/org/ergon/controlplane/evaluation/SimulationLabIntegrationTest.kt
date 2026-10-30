package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.contracts.ContractPromotionDatabase
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresEvaluationReportRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationFixtureRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationLabAuthority
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationReportDiscovery
import org.ergon.controlplane.evaluation.application.AuthorityRevocationIntent
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.SimulationAuthorityKind
import org.ergon.controlplane.evaluation.application.SimulationLabConflict
import org.ergon.controlplane.evaluation.application.SimulationLabPromotions
import org.ergon.controlplane.evaluation.application.SimulationLabUnavailable
import org.ergon.controlplane.evaluation.application.ViewerAttestation
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class SimulationLabIntegrationTest {
    @Test
    @Suppress("LongMethod") // Released-schema lifecycle checks original grants and historical promotions together.
    fun `released upgrade adds dedicated viewer grants and immutable revocations with exact retries`() {
        val db = database()
        val authority = authorities(db)
        val id = UUID.randomUUID()
        val intent = ViewerAttestation(db.actor, "trusted", "viewer-grant", db.now().plusHours(1).toInstant())
        val first = authority.attest(db.tenant, id, intent)
        assertThat(first.created).isTrue()
        assertThat(authority.attest(db.tenant, id, intent).authority).isEqualTo(first.authority)
        assertThat(authority.canView(db.tenant, db.actor)).isTrue()
        assertThat(authority.canView(UUID.randomUUID(), db.actor)).isFalse()
        val revoked = authority.revoke(db.tenant, SimulationAuthorityKind.EVALUATION_VIEWER, id, reason)
        assertThat(revoked.created).isTrue()
        assertThat(authority.revoke(db.tenant, SimulationAuthorityKind.EVALUATION_VIEWER, id, reason))
            .isEqualTo(revoked.copy(created = false))
        assertThat(authority.canView(db.tenant, db.actor)).isFalse()
        authority.attest(db.tenant, id, intent)
        assertThat(authority.canView(db.tenant, db.actor)).isFalse()
        assertThatThrownBy {
            authority.revoke(
                db.tenant,
                SimulationAuthorityKind.EVALUATION_VIEWER,
                id,
                reason.copy(reason = "changed"),
            )
        }.isInstanceOf(SimulationLabConflict::class.java)
        assertThatThrownBy { db.jdbc.sql("DELETE FROM evaluation_viewer_revocations").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            authority.revoke(
                UUID.randomUUID(),
                SimulationAuthorityKind.EVALUATION_VIEWER,
                id,
                reason,
            )
        }.isInstanceOf(SimulationLabUnavailable::class.java)
        val proof = db.grant()
        assertThat(authority.canView(db.tenant, db.actor)).isFalse()
        db.bind()
        val command = UUID.randomUUID()
        val receipt = db.service.promote(db.tenant, command, db.actor, db.intent()).receipt
        authority.revoke(db.tenant, SimulationAuthorityKind.CONTRACT_PROMOTER, proof.id, reason)
        assertThat(db.authorities.current(db.tenant, db.actor)).isNull()
        assertThat(db.service.get(db.tenant, command)).isEqualTo(receipt)
        assertThatThrownBy { db.service.promote(db.tenant, command, db.actor, db.intent()) }
            .isInstanceOf(PromotionUnavailable::class.java)
        assertThatThrownBy {
            db.runner.required {
                db.repository.appendPromotion(
                    db.tenant,
                    UUID.randomUUID(),
                    db.intent(),
                    proof,
                )
            }
        }.isInstanceOf(PromotionUnavailable::class.java)
        db.migration().validate()
        assertThat(
            db
                .migration()
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030195306")
    }

    @Test
    fun `revocation won during contract wait rolls back browser promotion and complete evidence links`() {
        val db = database()
        db.grant()
        db.bind()
        val viewer = viewer(db)
        val browser = browser(db)
        val command = UUID.randomUUID()
        val pool = Executors.newSingleThreadExecutor()
        val pid = LinkedBlockingQueue<Int>()
        try {
            val pending =
                db.runner.required {
                    db.repository.lock(db.tenant, db.key, UUID.randomUUID(), "binding")
                    val result =
                        pool.submit<Throwable?> {
                            try {
                                db.runner.required {
                                    pid.put(
                                        db.jdbc
                                            .sql("SELECT pg_backend_pid()")
                                            .query(Int::class.java)
                                            .single(),
                                    )
                                    browser.confirm(db.tenant, db.actor, command, db.intent())
                                }
                                null
                            } catch (error: RuntimeException) {
                                error
                            }
                        }
                    awaitBlocked(db, checkNotNull(pid.poll(10, TimeUnit.SECONDS)))
                    authorities(db).revoke(
                        db.tenant,
                        SimulationAuthorityKind.EVALUATION_VIEWER,
                        viewer,
                        reason,
                    )
                    result
                }
            assertThat(pending.get(15, TimeUnit.SECONDS)).isInstanceOf(SimulationLabUnavailable::class.java)
            assertThat(db.repository.promotion(db.tenant, command)).isNull()
            assertThat(
                db.jdbc
                    .sql("SELECT COUNT(*) FROM contract_promotion_reports WHERE tenant_id = :tenant")
                    .param("tenant", db.tenant)
                    .query(Long::class.java)
                    .single(),
            ).isZero()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `admitted promotion holds actor lock so revocation waits and preserves committed historical receipt`() {
        val db = database()
        val promoter = db.grant()
        db.bind()
        viewer(db)
        val browser = browser(db)
        val command = UUID.randomUUID()
        val pool = Executors.newSingleThreadExecutor()
        val pid = LinkedBlockingQueue<Int>()
        try {
            val pending =
                db.runner.required {
                    assertThat(browser.confirm(db.tenant, db.actor, command, db.intent()).created).isTrue()
                    val result =
                        pool.submit {
                            db.runner.required {
                                pid.put(
                                    db.jdbc
                                        .sql("SELECT pg_backend_pid()")
                                        .query(Int::class.java)
                                        .single(),
                                )
                                authorities(db).revoke(
                                    db.tenant,
                                    SimulationAuthorityKind.CONTRACT_PROMOTER,
                                    promoter.id,
                                    reason,
                                )
                            }
                        }
                    awaitBlocked(db, checkNotNull(pid.poll(10, TimeUnit.SECONDS)))
                    assertThat(result.isDone).isFalse()
                    result
                }
            pending.get(15, TimeUnit.SECONDS)
            assertThat(db.repository.promotion(db.tenant, command)).isNotNull()
            assertThatThrownBy { browser.confirm(db.tenant, db.actor, command, db.intent()) }
                .isInstanceOf(PromotionUnavailable::class.java)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `keyset discovery handles timestamp ties without duplicates and refuses foreign cursors`() {
        val db = database()
        // Equal server-recorded instants exercise the UUID tie-breaker without mutating immutable reports.
        db.runner.required {
            listOf(UUID.randomUUID(), UUID.randomUUID()).forEach { id ->
                db.jdbc
                    .sql(
                        """
                        INSERT INTO simulation_evaluation_reports
                            (tenant_id, evaluation_id, fixture_key, fixture_revision, fixture_digest,
                             report_digest, definition, passed, submitted_by, recorded_at)
                        SELECT tenant_id, :id, fixture_key, fixture_revision, fixture_digest,
                               report_digest, definition, passed, submitted_by, CURRENT_TIMESTAMP
                        FROM simulation_evaluation_reports WHERE tenant_id = :tenant AND evaluation_id = :source
                        """.trimIndent(),
                    ).param("id", id)
                    .param("tenant", db.tenant)
                    .param("source", db.h.reportId)
                    .update()
            }
        }
        val query = PostgresSimulationReportDiscovery(db.jdbc)
        val first = query.list(db.tenant, 2, null)
        val second = query.list(db.tenant, 2, first.last().reportId)
        assertThat(first + second).hasSize(3)
        assertThat((first + second).map { it.reportId }).doesNotHaveDuplicates()
        assertThat(query.list(UUID.randomUUID(), 2, null)).isEmpty()
        assertThatThrownBy { query.list(UUID.randomUUID(), 2, first.first().reportId) }
            .isInstanceOf(SimulationLabUnavailable::class.java)
        assertThatThrownBy { query.list(db.tenant, 52, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `expired viewer attestation fails current access and renewal needs a new evidence identity`() {
        val db = database()
        val authority = authorities(db)
        val id = UUID.randomUUID()
        val intent = ViewerAttestation(db.actor, "trusted", "short-grant", db.now().plusSeconds(3).toInstant())
        authority.attest(db.tenant, id, intent)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (db.now().toInstant().isBefore(intent.expiresAt)) {
            check(System.nanoTime() < deadline)
            Thread.sleep(20)
        }
        assertThat(authority.canView(db.tenant, db.actor)).isFalse()
        assertThat(authority.attest(db.tenant, id, intent).created).isFalse()
        assertThat(authority.canView(db.tenant, db.actor)).isFalse()
        viewer(db)
        assertThat(authority.canView(db.tenant, db.actor)).isTrue()
    }

    private fun database() = ContractPromotionDatabase(postgres, "20261030180229")

    private fun authorities(db: ContractPromotionDatabase) =
        PostgresSimulationLabAuthority(
            db.jdbc,
            db.transactions,
        )

    private fun viewer(db: ContractPromotionDatabase): UUID =
        UUID.randomUUID().also {
            authorities(db).attest(
                db.tenant,
                it,
                ViewerAttestation(
                    db.actor,
                    "trusted",
                    "viewer",
                    db.now().plusHours(1).toInstant(),
                ),
            )
        }

    private fun browser(db: ContractPromotionDatabase) =
        SimulationLabPromotions(
            authorities(db),
            db.runner,
            db.service,
            db.suites,
            db.h.codec,
            EvaluatePromotionEligibility(
                db.suites,
                db.h.codec,
                PostgresEvaluationReportRepository(db.jdbc, db.transactions, db.h.f.codec),
                db.h.f.codec,
                PostgresSimulationFixtureRepository(db.jdbc, db.transactions, db.h.f.packages),
                db.h.f.packages,
            ),
        )

    private fun awaitBlocked(
        db: ContractPromotionDatabase,
        pid: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!db.jdbc
                .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                .param("pid", pid)
                .query(Boolean::class.java)
                .single()
        ) {
            check(System.nanoTime() < deadline) { "expected database lock wait" }
            Thread.sleep(20)
        }
    }

    companion object {
        private val reason = AuthorityRevocationIntent("trusted", "revocation-command", "Access withdrawn")

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
