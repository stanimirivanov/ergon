package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.RequesterCaseNotFoundException
import org.ergon.controlplane.cases.application.TransactionRunner
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RequesterInformationRequestReadsTest {
    @Test
    fun `unavailable work never reaches case or question storage`() {
        val cases = mock(RequesterCaseWriteGuard::class.java)
        val questions = mock(OwnedInformationRequestsReader::class.java)
        val service =
            OwnedInformationRequestsService(
                mock(HumanFollowUpClaimRepository::class.java),
                cases,
                questions,
                object : TransactionRunner {
                    override fun <T : Any> required(block: () -> T): T = block()
                },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            )
        assertThatThrownBy { service.list(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()) }
            .isInstanceOf(RequesterInformationRequestNotFoundException::class.java)
        verifyNoInteractions(cases, questions)
    }

    @Test
    fun `requester read forwards verified identity and distinguishes empty ownership from absence`() {
        val tenant = UUID.randomUUID()
        val case = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val empty = RequesterCaseQuestionsPage(emptyList(), truncated = false)
        val service =
            RequesterCaseQuestionsService { t, c, a ->
                assertThat(t.value).isEqualTo(tenant)
                assertThat(c.value).isEqualTo(case)
                assertThat(a.value).isEqualTo(actor)
                empty
            }
        assertThat(service.list(tenant, case, actor)).isEqualTo(empty)
        assertThatThrownBy { RequesterCaseQuestionsService { _, _, _ -> null }.list(tenant, case, actor) }
            .isInstanceOf(RequesterCaseNotFoundException::class.java)
    }
}
