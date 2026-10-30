package org.ergon.controlplane.contracts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresCurrentContractPromotion
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class CurrentContractPromotionIntegrationTest {
    @Test
    fun `only exact tenant revision and current binding qualify without renewing historical promoter authority`() {
        val db = ContractPromotionDatabase(postgres)
        val query = PostgresCurrentContractPromotion(db.jdbc)
        val identity = identity(db)
        assertThatThrownBy { query.existsWhileLocked(db.tenant, identity) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(db.runner.required { query.existsWhileLocked(db.tenant, identity) }).isFalse()
        db.grant()
        db.bind()
        assertThat(db.runner.required { query.existsWhileLocked(db.tenant, identity) }).isFalse()
        val id = UUID.randomUUID()
        val receipt = db.service.promote(db.tenant, id, db.actor, db.intent()).receipt
        assertThat(db.runner.required { query.existsWhileLocked(db.tenant, identity) }).isTrue()
        assertThat(db.runner.required { query.existsWhileLocked(UUID.randomUUID(), identity) }).isFalse()
        val oldPin = identity.copy(revision = ResolutionContractRevision.of(2))
        assertThat(db.runner.required { query.existsWhileLocked(db.tenant, oldPin) }).isFalse()
        db.service.bind(
            db.tenant,
            db.key,
            UUID.randomUUID(),
            db.actor,
            RequiredSuiteIntent(1, db.suite, "New gate"),
        )
        assertThat(db.runner.required { query.existsWhileLocked(db.tenant, identity) }).isFalse()
        assertThat(db.service.get(db.tenant, id)).isEqualTo(receipt)
        db.service.promote(db.tenant, UUID.randomUUID(), db.actor, db.intent().copy(expectedVersion = 2))
        assertThat(db.runner.required { query.existsWhileLocked(db.tenant, identity) }).isTrue()
    }

    @Test
    fun `binding change cannot complete while admitted transaction retains the contract lock`() {
        val db = ready()
        val query = PostgresCurrentContractPromotion(db.jdbc)
        val pool = Executors.newSingleThreadExecutor()
        val pid = LinkedBlockingQueue<Int>()
        try {
            val future =
                db.runner.required {
                    assertThat(query.existsWhileLocked(db.tenant, identity(db))).isTrue()
                    val submitted =
                        pool.submit {
                            db.runner.required {
                                pid.put(backendPid(db))
                                db.service.bind(
                                    db.tenant,
                                    db.key,
                                    UUID.randomUUID(),
                                    db.actor,
                                    RequiredSuiteIntent(1, db.suite, "Change while admission is active"),
                                )
                            }
                        }
                    awaitBlocked(db, checkNotNull(pid.poll(10, TimeUnit.SECONDS)))
                    assertThat(submitted.isDone).isFalse()
                    assertThat(query.existsWhileLocked(db.tenant, identity(db))).isTrue()
                    submitted
                }
            future.get(15, TimeUnit.SECONDS)
            assertThat(db.runner.required { query.existsWhileLocked(db.tenant, identity(db)) }).isFalse()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `waiting admission observes committed gate change and a subsequently committed fresh promotion`() {
        val db = ready()
        val query = PostgresCurrentContractPromotion(db.jdbc)
        val pool = Executors.newSingleThreadExecutor()
        val pid = LinkedBlockingQueue<Int>()
        try {
            val denied =
                db.runner.required {
                    db.repository.lock(db.tenant, db.key, UUID.randomUUID(), "binding")
                    val future =
                        pool.submit<Boolean> {
                            db.runner.required {
                                pid.put(backendPid(db))
                                query.existsWhileLocked(db.tenant, identity(db))
                            }
                        }
                    awaitBlocked(db, checkNotNull(pid.poll(10, TimeUnit.SECONDS)))
                    db.service.bind(
                        db.tenant,
                        db.key,
                        UUID.randomUUID(),
                        db.actor,
                        RequiredSuiteIntent(1, db.suite, "New gate"),
                    )
                    future
                }
            assertThat(denied.get(15, TimeUnit.SECONDS)).isFalse()
            val admitted =
                db.runner.required {
                    db.service.promote(db.tenant, UUID.randomUUID(), db.actor, db.intent().copy(expectedVersion = 2))
                    val future =
                        pool.submit<Boolean> {
                            db.runner.required {
                                pid.put(backendPid(db))
                                query.existsWhileLocked(db.tenant, identity(db))
                            }
                        }
                    awaitBlocked(db, checkNotNull(pid.poll(10, TimeUnit.SECONDS)))
                    future
                }
            assertThat(admitted.get(15, TimeUnit.SECONDS)).isTrue()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ready(): ContractPromotionDatabase =
        ContractPromotionDatabase(postgres).also {
            it.grant()
            it.bind()
            it.service.promote(it.tenant, UUID.randomUUID(), it.actor, it.intent())
        }

    private fun identity(db: ContractPromotionDatabase) =
        ResolutionContractIdentity(
            ResolutionContractKey.of(db.key),
            ResolutionContractRevision.of(
                db
                    .intent()
                    .contract.revision
                    .toInt(),
            ),
        )

    private fun backendPid(db: ContractPromotionDatabase) =
        db.jdbc
            .sql("SELECT pg_backend_pid()")
            .query(Int::class.java)
            .single()

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
            check(System.nanoTime() < deadline) { "query did not wait for governing-contract lock" }
            Thread.sleep(20)
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
