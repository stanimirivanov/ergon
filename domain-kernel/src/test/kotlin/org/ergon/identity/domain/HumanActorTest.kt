package org.ergon.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class HumanActorTest {
    @Test
    fun `normalizes an opaque external identity binding`() {
        val actor = HumanActor.create(HumanActorId(UUID.randomUUID()), " workforce-sso ", " employee-42 ")

        assertThat(actor.identityProvider).isEqualTo("workforce-sso")
        assertThat(actor.subject).isEqualTo("employee-42")
    }

    @Test
    fun `rejects an invalid provider or blank subject`() {
        val id = HumanActorId(UUID.randomUUID())

        assertThatThrownBy { HumanActor.create(id, "Workforce SSO", "employee-42") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HumanActor.create(id, "workforce-sso", " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
