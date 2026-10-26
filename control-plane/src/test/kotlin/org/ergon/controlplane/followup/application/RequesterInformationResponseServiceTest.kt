package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.followup.domain.InvalidRequesterInformationAnswerException
import org.ergon.followup.domain.RequesterInformationAnswer
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.followup.domain.RequesterInformationResponseId
import org.ergon.identity.domain.HumanActorId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RequesterInformationResponseServiceTest {
    private val now = Instant.parse("2026-10-26T14:00:00Z")
    private val command =
        RequesterInformationResponseCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            " exact 😀 ",
        )
    private val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T = block()
        }

    @Test
    fun `hidden targets and invalid text never reach storage`() {
        val service =
            service(
                RequesterInformationResponseRepository { _, _ -> error("must not write") },
                RequesterAnswerWriteGuard { _, _, _, _ -> false },
            )
        assertThatThrownBy { service.record(command) }
            .isInstanceOf(RequesterInformationResponseNotFoundException::class.java)
        assertThatThrownBy { service.record(command.copy(answer = "\u00A0")) }
            .isInstanceOf(InvalidRequesterInformationAnswerException::class.java)
    }

    @Test
    fun `replay accepts original server time without replacing exact source text`() {
        val service =
            service(
                RequesterInformationResponseRepository { _, intent ->
                    val historical = stored(intent.copy(respondedAt = now.minusSeconds(30)))
                    RequesterInformationResponseRecording(historical, false)
                },
            )
        val result = service.record(command)
        assertThat(result.created).isFalse()
        assertThat(result.stored.response.answer.value).isEqualTo(command.answer)
        assertThat(result.stored.response.respondedAt).isEqualTo(now.minusSeconds(30))
    }

    @Test
    fun `every changed intent or foreign attribution conflicts instead of disclosing a winner`() {
        val changedResponseId = RequesterInformationResponseId(UUID.randomUUID())
        val mutations: List<(StoredRequesterInformationResponse) -> StoredRequesterInformationResponse> =
            listOf(
                { it.copy(caseId = CaseId(UUID.randomUUID())) },
                { it.copy(requesterActorId = HumanActorId(UUID.randomUUID())) },
                { it.copy(response = it.response.copy(responseId = changedResponseId)) },
                { it.copy(response = it.response.copy(requestId = RequesterInformationRequestId(UUID.randomUUID()))) },
                { it.copy(response = it.response.copy(answer = RequesterInformationAnswer("changed"))) },
            )
        for (mutate in mutations) {
            val service =
                service(
                    RequesterInformationResponseRepository { _, intent ->
                        RequesterInformationResponseRecording(mutate(stored(intent)), false)
                    },
                )
            assertThatThrownBy { service.record(command) }
                .isInstanceOf(RequesterInformationResponseConflictException::class.java)
        }
    }

    @Test
    fun `storage defects propagate rather than acknowledging uncertain persistence`() {
        val defect = IllegalStateException("synthetic storage failure")
        val service = service(RequesterInformationResponseRepository { _, _ -> throw defect })
        assertThatThrownBy { service.record(command) }.isSameAs(defect)
    }

    private fun stored(intent: RequesterInformationResponse) =
        StoredRequesterInformationResponse(intent, CaseId(command.caseId), HumanActorId(command.actorId), now)

    private fun service(
        repository: RequesterInformationResponseRepository,
        guard: RequesterAnswerWriteGuard = RequesterAnswerWriteGuard { _, _, _, _ -> true },
    ) = RequesterInformationResponseService(guard, repository, transactions, Clock.fixed(now, ZoneOffset.UTC))
}
