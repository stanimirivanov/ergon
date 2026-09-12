package org.ergon.cases.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ErgonCaseTest {
    @Test
    fun `opens a case from a normalized goal and requester observation`() {
        val observation = requesterObservation("  I cannot sign in.  ")

        val case =
            ErgonCase.open(
                id = CASE_ID,
                tenantId = TENANT_ID,
                goal = CaseGoal.of("  Restore workspace access  "),
                initialObservation = observation,
            )

        assertThat(case.goal.value).isEqualTo("Restore workspace access")
        assertThat(case.status).isEqualTo(CaseStatus.OPEN)
        assertThat(case.streamVersion).isEqualTo(1)
        assertThat(case.pendingEvents().single())
            .isEqualTo(
                CaseOpened(
                    goal = "Restore workspace access",
                    observationId = OBSERVATION_ID.value,
                    observationOriginType = ObservationOriginType.REQUESTER,
                    observationProvider = "api",
                    observationReference = null,
                    observationContent = "I cannot sign in.",
                    occurredAt = OCCURRED_AT,
                ),
            )
    }

    @Test
    fun `rehydrates a case and records a connector observation at the next version`() {
        val opened =
            ErgonCase.open(
                id = CASE_ID,
                tenantId = TENANT_ID,
                goal = CaseGoal.of("Restore workspace access"),
                initialObservation = requesterObservation("I cannot sign in."),
            )
        val case = ErgonCase.rehydrate(CASE_ID, TENANT_ID, opened.pendingEvents())

        case.record(
            SourceObservation.create(
                id = ObservationId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                origin = ObservationOrigin.connector("identity-stub", "accounts/customer-42"),
                content = "status=LOCKED",
                observedAt = OCCURRED_AT.plusSeconds(1),
            ),
        )

        assertThat(case.streamVersion).isEqualTo(2)
        assertThat(case.pendingEvents().single()).isInstanceOf(ObservationRecorded::class.java)
    }

    @Test
    fun `binds account access state to an existing connector observation`() {
        val connectorObservationId = ObservationId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
        val case = caseWithConnectorObservation(connectorObservationId)

        case.bindAccountAccessState(
            factId = FactId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
            observationId = connectorObservationId,
            state = AccountAccessState.LOCKED,
            boundAt = OCCURRED_AT.plusSeconds(2),
        )

        assertThat(case.streamVersion).isEqualTo(3)
        assertThat(case.pendingEvents().single())
            .isEqualTo(
                AccountAccessStateBound(
                    factId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    observationId = connectorObservationId.value,
                    accountReference = "accounts/customer-42",
                    state = AccountAccessState.LOCKED,
                    occurredAt = OCCURRED_AT.plusSeconds(2),
                ),
            )
    }

    @Test
    fun `rejects account state without attributable connector evidence`() {
        val connectorObservationId = ObservationId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
        val case = caseWithConnectorObservation(connectorObservationId)
        val factId = FactId(UUID.fromString("44444444-4444-4444-4444-444444444444"))

        assertThatIllegalArgumentException().isThrownBy {
            case.bindAccountAccessState(
                factId,
                ObservationId(UUID.randomUUID()),
                AccountAccessState.LOCKED,
                OCCURRED_AT,
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            case.bindAccountAccessState(factId, OBSERVATION_ID, AccountAccessState.LOCKED, OCCURRED_AT)
        }

        case.bindAccountAccessState(factId, connectorObservationId, AccountAccessState.LOCKED, OCCURRED_AT)
        assertThatIllegalArgumentException().isThrownBy {
            case.bindAccountAccessState(
                FactId(UUID.randomUUID()),
                connectorObservationId,
                AccountAccessState.ACTIVE,
                OCCURRED_AT,
            )
        }
    }

    @Test
    fun `rejects blank goals and malformed connector providers`() {
        assertThatIllegalArgumentException().isThrownBy { CaseGoal.of(" ") }
        assertThatIllegalArgumentException().isThrownBy {
            ObservationOrigin.connector("Identity Stub", "accounts/customer-42")
        }
    }

    @Test
    fun `rejects opening a case from a connector observation`() {
        assertThatIllegalArgumentException().isThrownBy {
            ErgonCase.open(
                id = CASE_ID,
                tenantId = TENANT_ID,
                goal = CaseGoal.of("Restore workspace access"),
                initialObservation =
                    SourceObservation.create(
                        id = OBSERVATION_ID,
                        origin = ObservationOrigin.connector("identity-stub", "accounts/customer-42"),
                        content = "status=LOCKED",
                        observedAt = OCCURRED_AT,
                    ),
            )
        }
    }

    private fun requesterObservation(content: String) =
        SourceObservation.create(
            id = OBSERVATION_ID,
            origin = ObservationOrigin.requesterApi(),
            content = content,
            observedAt = OCCURRED_AT,
        )

    private fun caseWithConnectorObservation(observationId: ObservationId): ErgonCase {
        val opened =
            ErgonCase.open(
                id = CASE_ID,
                tenantId = TENANT_ID,
                goal = CaseGoal.of("Restore workspace access"),
                initialObservation = requesterObservation("I cannot sign in."),
            )
        opened.record(
            SourceObservation.create(
                id = observationId,
                origin = ObservationOrigin.connector("identity-stub", "accounts/customer-42"),
                content = "status=LOCKED",
                observedAt = OCCURRED_AT.plusSeconds(1),
            ),
        )
        return ErgonCase.rehydrate(CASE_ID, TENANT_ID, opened.pendingEvents())
    }

    private companion object {
        val CASE_ID = CaseId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val TENANT_ID = TenantId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        val OBSERVATION_ID = ObservationId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        val OCCURRED_AT: Instant = Instant.parse("2026-09-11T10:15:30Z")
    }
}
