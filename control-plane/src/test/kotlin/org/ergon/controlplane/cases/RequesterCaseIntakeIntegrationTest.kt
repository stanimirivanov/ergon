package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.cases.application.RequesterCaseIntakeCommand
import org.ergon.controlplane.cases.application.RequesterCaseIntakeConflictException
import org.ergon.controlplane.cases.application.RequesterCaseIntakeService
import org.ergon.controlplane.cases.application.RequesterCaseProgressService
import org.ergon.controlplane.identity.application.HumanAuthorityService
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequesterCaseIntakeIntegrationTest(
    @Autowired private val intake: RequesterCaseIntakeService,
    @Autowired private val progress: RequesterCaseProgressService,
    @Autowired private val cases: CaseCommandService,
    @Autowired private val actors: HumanAuthorityService,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `opening and ownership commit together without approval or a run`() {
        val command = command()
        val result = intake.open(command)
        assertThat(result.created).isTrue()
        assertThat(result.receipt.goal).isEqualTo("Restore access")
        assertThat(result.receipt.initialObservation).isEqualTo("<script>untrusted</script> 😀")
        val visible = progress.progress(command.tenantId, command.requesterActorId, command.caseId)
        assertThat(visible.goal).isEqualTo(result.receipt.goal)
        assertThat(visible.openedAt).isEqualTo(result.receipt.openedAt)
        for (table in listOf("cases", "case_events", "case_timeline_entries", "case_requesters")) {
            assertThat(count(table, command)).isEqualTo(1)
        }
        assertThat(count("resolution_runs", command)).isZero()
    }

    @Test
    fun `recovery returns the immutable opening after later events and closure`() {
        val command = command()
        val original = intake.open(command).receipt
        cases.recordConnectorObservation(
            RecordConnectorObservationCommand(
                command.tenantId,
                command.caseId,
                1,
                "test",
                "ref",
                "Later private evidence",
            ),
        )
        // Simulate only a later lifecycle projection; this fixture does not establish accepted proof.
        jdbc
            .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :tenant AND case_id = :case")
            .param("tenant", command.tenantId)
            .param("case", command.caseId)
            .update()
        val recovered =
            intake.open(command.copy(goal = "Restore access", initialObservation = original.initialObservation))
        assertThat(recovered.created).isFalse()
        assertThat(recovered.receipt).isEqualTo(original)
        assertThat(count("case_events", command)).isEqualTo(2)
        assertThat(count("case_requesters", command)).isEqualTo(1)
    }

    @Test
    fun `changed intent actor and legacy collisions never return or adopt an opening`() {
        val command = command()
        intake.open(command)
        val otherActor = actor(command.tenantId)
        for (conflict in listOf(
            command.copy(goal = "Different"),
            command.copy(initialObservation = "Different"),
            command.copy(requesterActorId = otherActor),
        )) {
            assertThatThrownBy { intake.open(conflict) }.isInstanceOf(RequesterCaseIntakeConflictException::class.java)
        }
        val legacy = cases.open(OpenCaseCommand(command.tenantId, command.goal, command.initialObservation))
        val collision = command.copy(caseId = legacy.caseId)
        assertThatThrownBy { intake.open(collision) }.isInstanceOf(RequesterCaseIntakeConflictException::class.java)
        jdbc
            .sql(
                """
                INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
                VALUES (:tenant, :case, :actor, 'reviewed-bootstrap')
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("case", legacy.caseId)
            .param("actor", command.requesterActorId)
            .update()
        assertThatThrownBy { intake.open(collision) }.isInstanceOf(RequesterCaseIntakeConflictException::class.java)
        assertThat(count("case_events", command)).isEqualTo(1)
        assertThat(count("case_events", collision)).isEqualTo(1)
    }

    @Test
    fun `foreign or missing actor rolls back event projection and binding while tenant identities stay independent`() {
        val command = command()
        intake.open(command)
        val foreign = command.copy(tenantId = UUID.randomUUID())
        for (invalid in listOf(foreign, foreign.copy(requesterActorId = UUID.randomUUID()))) {
            assertThatThrownBy { intake.open(invalid) }.isInstanceOf(DataAccessException::class.java)
            for (table in listOf("cases", "case_events", "case_timeline_entries", "case_requesters")) {
                assertThat(count(table, invalid)).isZero()
            }
        }
        val independent = foreign.copy(requesterActorId = actor(foreign.tenantId), goal = "Independent")
        assertThat(intake.open(independent).created).isTrue()
        assertThat(intake.open(command).receipt.goal).isEqualTo("Restore access")
        assertThat(intake.open(independent).receipt.goal).isEqualTo("Independent")
    }

    @Test
    fun `concurrent exact commands share one whole receipt and competing content cannot merge`() {
        val command = command()
        val results = RunEscalationTestFixture.together({ intake.open(command) }, { intake.open(command) })
        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results.map { it.receipt }.distinct()).hasSize(1)
        assertThat(count("case_events", command)).isEqualTo(1)
        val fresh = command.copy(caseId = UUID.randomUUID())
        val competing =
            RunEscalationTestFixture.together(
                { runCatching { intake.open(fresh) } },
                { runCatching { intake.open(fresh.copy(initialObservation = "Competing")) } },
            )
        assertThat(competing.count { it.isSuccess }).isEqualTo(1)
        assertThat(competing.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(RequesterCaseIntakeConflictException::class.java)
        assertThat(count("case_events", fresh)).isEqualTo(1)
        assertThat(count("case_requesters", fresh)).isEqualTo(1)
    }

    @Test
    fun `invalid text cannot leave partial cases or ownership`() {
        val command = command()
        for (invalid in listOf(
            command.copy(goal = " "),
            command.copy(goal = "x".repeat(501)),
            command.copy(initialObservation = " "),
            command.copy(initialObservation = "x".repeat(8001)),
            command.copy(goal = "bad\u0000goal"),
            command.copy(initialObservation = "bad\uD800text"),
        )) {
            assertThatThrownBy { intake.open(invalid) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(count("cases", command)).isZero()
        assertThat(count("case_events", command)).isZero()
        assertThat(count("case_requesters", command)).isZero()
    }

    private fun command(): RequesterCaseIntakeCommand {
        val tenant = UUID.randomUUID()
        return RequesterCaseIntakeCommand(
            tenant,
            UUID.randomUUID(),
            actor(tenant),
            " Restore access ",
            " <script>untrusted</script> 😀 ",
        )
    }

    private fun actor(tenant: UUID): UUID =
        actors
            .registerActor(tenant, "test", UUID.randomUUID().toString())
            .actor.id.value

    private fun count(
        table: String,
        command: RequesterCaseIntakeCommand,
    ): Long =
        // Table names are a fixed test-owned set, never supplied by an untrusted caller.
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant AND case_id = :case")
            .param("tenant", command.tenantId)
            .param("case", command.caseId)
            .query(Long::class.java)
            .single()

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
