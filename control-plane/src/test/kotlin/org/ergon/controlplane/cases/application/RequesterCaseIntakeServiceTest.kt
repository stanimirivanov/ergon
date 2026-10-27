package org.ergon.controlplane.cases.application

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RequesterCaseIntakeServiceTest {
    private val repository = mock(RequesterCaseIntakeRepository::class.java)
    private val committer = mock(CaseEventCommitter::class.java)
    private val command =
        RequesterCaseIntakeCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Goal", "Input")

    @Test
    fun `invalid input has a distinct safe rejection before storage`() {
        for (invalid in listOf(command.copy(goal = " "), command.copy(initialObservation = "bad\u0000text"))) {
            assertThatThrownBy { service().open(invalid) }
                .isInstanceOf(InvalidRequesterCaseIntakeException::class.java)
                .hasMessage("Invalid requester case intake")
        }
        verifyNoInteractions(repository, committer)
    }

    @Test
    fun `codec or storage illegal arguments remain defects rather than editable rejection`() {
        val defect = IllegalArgumentException("unsupported stored schema")
        `when`(repository.find(TenantId(command.tenantId), CaseId(command.caseId))).thenThrow(defect)
        assertThatThrownBy { service().open(command) }.isSameAs(defect)
        verifyNoInteractions(committer)
    }

    @Test
    fun `identity failures are outside input normalization`() {
        val defect = IllegalArgumentException("identity generator failed")
        assertThatThrownBy { service(IdentityGenerator { throw defect }).open(command) }.isSameAs(defect)
        verifyNoInteractions(repository, committer)
    }

    private fun service(ids: IdentityGenerator = IdentityGenerator(UUID::randomUUID)): RequesterCaseIntakeService =
        RequesterCaseIntakeService(
            repository,
            committer,
            ids,
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            },
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )
}
