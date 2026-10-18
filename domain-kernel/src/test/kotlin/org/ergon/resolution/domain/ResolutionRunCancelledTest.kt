package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ResolutionRunCancelledTest {
    @Test
    fun `only initial states can become a sequence-one cancellation`() {
        ResolutionRunState.entries.forEach { state ->
            val create = {
                ResolutionRunCancelled(
                    ResolutionRunEventId(UUID.randomUUID()),
                    ResolutionRunId(UUID.randomUUID()),
                    state,
                    Instant.EPOCH,
                )
            }
            if (state in setOf(ResolutionRunState.WAITING_FOR_APPROVAL, ResolutionRunState.READY_FOR_AUTHORIZATION)) {
                val event = create()
                assertThat(event.sequence).isEqualTo(1)
                assertThat(event.toState).isEqualTo(ResolutionRunState.CANCELLED)
                assertThat(event.type).isEqualTo(ResolutionRunEventType.RUN_CANCELLED)
                assertThat(event.reason).isEqualTo(ResolutionRunCancellationReason.OPERATOR_REQUESTED)
            } else {
                assertThatThrownBy { create() }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }
}
