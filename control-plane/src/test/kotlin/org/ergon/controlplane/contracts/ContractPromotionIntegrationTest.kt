package org.ergon.controlplane.contracts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.contracts.application.PromotionAuthorityIntent
import org.ergon.controlplane.contracts.application.PromotionConflict
import org.ergon.controlplane.contracts.application.PromotionIneligible
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class ContractPromotionIntegrationTest {
    @Test
    @Suppress("LongMethod") // One upgrade journey verifies retained inputs and immutable promotion attribution.
    fun `upgrade promotes exact evidence with dedicated authority immutable receipts and original retry proof`() {
        val db = ContractPromotionDatabase(postgres)
        val proof = db.grant()
        val grantRetry =
            db.authorities.attest(
                db.tenant,
                proof.id,
                PromotionAuthorityIntent(db.actor, proof.sourceProvider, proof.sourceReference, proof.expiresAt),
            )
        assertThat(grantRetry.created).isFalse()
        assertThat(grantRetry.authority).isEqualTo(proof)
        val binding = db.bind()
        assertThat(binding.binding.version).isEqualTo(1)
        val id = UUID.randomUUID()
        val first = db.service.promote(db.tenant, id, db.actor, db.intent())
        assertThat(first.created).isTrue()
        db.grant(seconds = 7200)
        val retry = db.service.promote(db.tenant, id, db.actor, db.intent())
        assertThat(retry.created).isFalse()
        assertThat(retry.receipt).isEqualTo(first.receipt)
        assertThat(first.receipt.authorityId).isEqualTo(proof.id)
        assertThat(db.repository.promotion(UUID.randomUUID(), id)).isNull()
        val other = db.actor(db.tenant)
        db.grant(other)
        assertThatThrownBy { db.service.promote(db.tenant, id, other, db.intent()) }
            .isInstanceOf(PromotionConflict::class.java)
        assertThatThrownBy {
            db.service.promote(db.tenant, UUID.randomUUID(), db.actor, db.intent().copy(reports = emptyList()))
        }.isInstanceOf(PromotionIneligible::class.java)
        assertThatThrownBy { db.service.promote(db.tenant, id, db.actor, db.intent().copy(rationale = "different")) }
            .isInstanceOf(PromotionConflict::class.java)
        val resolver = db.actor(db.tenant)
        db.jdbc
            .sql(
                """
                INSERT INTO approval_authority_evidence
                    (tenant_id, evidence_id, actor_id, authority, source_provider, source_reference, attested_at, expires_at)
                VALUES (:tenant, :id, :actor, 'RESOLVER', 'test', 'resolver-only', clock_timestamp(), clock_timestamp() + INTERVAL '1 hour')
                """.trimIndent(),
            ).param("tenant", db.tenant)
            .param("id", UUID.randomUUID())
            .param("actor", resolver)
            .update()
        assertThatThrownBy { db.service.promote(db.tenant, UUID.randomUUID(), resolver, db.intent()) }
            .isInstanceOf(PromotionUnavailable::class.java)
        assertThatThrownBy { db.service.promote(UUID.randomUUID(), UUID.randomUUID(), db.actor, db.intent()) }
            .isInstanceOf(PromotionUnavailable::class.java)
        concurrentRetry(db)
        assertThatThrownBy { db.jdbc.sql("UPDATE contract_promotions SET actor_id = actor_id").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { db.jdbc.sql("DELETE FROM contract_required_suite_bindings").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { db.jdbc.sql("DELETE FROM contract_promotion_authorities").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { db.jdbc.sql("DELETE FROM contract_promotion_reports").update() }
            .isInstanceOf(DataAccessException::class.java)
        directAdmissionRefusal(db)
    }

    private fun directAdmissionRefusal(db: ContractPromotionDatabase) {
        val command = UUID.randomUUID()
        val valid = checkNotNull(db.authorities.current(db.tenant, db.actor))
        val invalid = db.intent().copy(reports = emptyList())
        assertThatThrownBy {
            db.runner.required {
                db.repository.lock(db.tenant, db.key, command, "promotion")
                db.repository.appendPromotion(db.tenant, command, invalid, valid)
            }
        }.isInstanceOf(PromotionConflict::class.java)
        assertThat(db.repository.promotion(db.tenant, command)).isNull()
        assertThat(
            db.jdbc
                .sql("SELECT COUNT(*) FROM contract_promotion_reports WHERE tenant_id = :tenant AND promotion_id = :id")
                .param("tenant", db.tenant)
                .param("id", command)
                .query(Long::class.java)
                .single(),
        ).isZero()
    }

    @Test
    fun `required suite update serializes with waiting promotion and rejects stale reviewed version`() {
        val db = ContractPromotionDatabase(postgres)
        db.grant()
        db.bind()
        val command = UUID.randomUUID()
        waitingCommand(db, { db.service.promote(db.tenant, command, db.actor, db.intent()) }) {
            db.service.bind(
                db.tenant,
                db.key,
                UUID.randomUUID(),
                db.actor,
                RequiredSuiteIntent(1, db.suite, "Reconfirm gate"),
            )
        }.let { failure -> assertThat(failure).isInstanceOf(PromotionConflict::class.java) }
        assertThat(db.repository.promotion(db.tenant, command)).isNull()
        assertThat(db.service.current(db.tenant, db.key).version).isEqualTo(2)
    }

    @Test
    fun `authority expiring during a database lock wait is rechecked and refused`() {
        val db = ContractPromotionDatabase(postgres)
        db.grant()
        db.bind()
        val shortActor = db.actor(db.tenant)
        val proof = db.grant(shortActor, seconds = 2)
        val command = UUID.randomUUID()
        val failure =
            waitingCommand(db, { db.service.promote(db.tenant, command, shortActor, db.intent()) }) {
                await { !db.now().toInstant().isBefore(proof.expiresAt) }
            }
        assertThat(failure).isInstanceOf(PromotionUnavailable::class.java)
        assertThat(db.repository.promotion(db.tenant, command)).isNull()
    }

    private fun waitingCommand(
        db: ContractPromotionDatabase,
        action: () -> Any,
        whileBlocked: () -> Unit,
    ): Throwable? {
        val pool = Executors.newSingleThreadExecutor()
        val pid = LinkedBlockingQueue<Int>()
        try {
            val future =
                db.runner.required {
                    db.repository.lock(db.tenant, db.key, UUID.randomUUID(), "binding")
                    val submitted =
                        pool.submit<Throwable?> {
                            try {
                                db.runner.required {
                                    pid.put(
                                        db.jdbc
                                            .sql("SELECT pg_backend_pid()")
                                            .query(Int::class.java)
                                            .single(),
                                    )
                                    action()
                                }
                                null
                            } catch (error: RuntimeException) {
                                error
                            }
                        }
                    val blockedPid = checkNotNull(pid.poll(10, TimeUnit.SECONDS))
                    await {
                        db.jdbc
                            .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                            .param("pid", blockedPid)
                            .query(Boolean::class.java)
                            .single()
                    }
                    whileBlocked()
                    submitted
                }
            return future.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun concurrentRetry(db: ContractPromotionDatabase) {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val id = UUID.randomUUID()
        try {
            val futures =
                (1..2).map {
                    pool.submit<Boolean> {
                        check(start.await(10, TimeUnit.SECONDS))
                        db.service.promote(db.tenant, id, db.actor, db.intent()).created
                    }
                }
            start.countDown()
            assertThat(futures.map { it.get(15, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "database condition was not reached" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `complete empty migration chain validates`() {
        val schema = "empty_promotion_" + UUID.randomUUID().toString().replace("-", "")
        val flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
        flyway.migrate()
        flyway.validate()
        assertThat(
            flyway
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030180229")
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
