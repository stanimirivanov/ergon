package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RecordedRunTransitionsTest {
    private val runId = ResolutionRunId(UUID.randomUUID())
    private val now = Instant.parse("2026-10-13T10:00:00Z")
    private val start =
        StoredResolutionRunStart(
            ResolutionRunStart(
                runId,
                CaseId(UUID.randomUUID()),
                1,
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-access"),
                    ResolutionContractRevision.of(1),
                ),
                ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                ResolutionStepId.of("unlock"),
                CapabilityName.of("account.unlock"),
                StepRisk.MEDIUM,
                ApprovalRequirement.RESOLVER,
                ResolutionRunInitialState.WAITING_FOR_APPROVAL,
            ),
            now,
        )

    @Test
    fun `zero revision has no transitions and retains the initial requirement state`() {
        assertThatCode {
            checkRecordedRunTransitions(start, state(ResolutionRunState.WAITING_FOR_APPROVAL, 0, now), emptyList())
        }.doesNotThrowAnyException()
    }

    @Test
    fun `no human approval chains from readiness rather than waiting`() {
        val readyStart =
            start.copy(
                run =
                    start.run.copy(
                        requiredApproval = ApprovalRequirement.NONE,
                        initialState = ResolutionRunInitialState.READY_FOR_AUTHORIZATION,
                    ),
            )
        val first = success().copy(fromState = ResolutionRunState.READY_FOR_AUTHORIZATION)
        val projected = state(ResolutionRunState.VERIFYING, 1, now)
        assertThatCode { checkRecordedRunTransitions(readyStart, projected, listOf(first)) }
            .doesNotThrowAnyException()
        assertThatThrownBy { checkRecordedRunTransitions(readyStart, projected, listOf(success())) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `append sequence remains authoritative after wall clock correction`() {
        val first = success()
        val last =
            RecordedRunTransition(
                runId,
                2,
                ResolutionRunEventType.OUTCOME_PROOF_ACCEPTED,
                ResolutionRunState.VERIFYING,
                ResolutionRunState.VERIFIED_RESOLVED,
                now.minusSeconds(1),
                now.minusSeconds(1),
            )
        assertThatCode {
            checkRecordedRunTransitions(start, state(last.toState, 2, last.occurredAt), listOf(first, last))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `missing foreign reordered and stale tails fail closed`() {
        val first = success()
        val last =
            RecordedRunTransition(
                runId,
                2,
                ResolutionRunEventType.OUTCOME_PROOF_ACCEPTED,
                ResolutionRunState.VERIFYING,
                ResolutionRunState.VERIFIED_RESOLVED,
                now,
                now,
            )
        val terminal = state(last.toState, 2, now)
        listOf(
            emptyList(),
            listOf(first),
            listOf(last, first),
            listOf(first.copy(runId = ResolutionRunId(UUID.randomUUID())), last),
            listOf(first, last.copy(occurredAt = now.plusNanos(1))),
        ).forEach { events ->
            assertThatThrownBy { checkRecordedRunTransitions(start, terminal, events) }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `failed runs support retry or escalation metadata but not a proof transition`() {
        val failed =
            RecordedRunTransition(
                runId,
                1,
                ResolutionRunEventType.CAPABILITY_FAILED,
                ResolutionRunState.WAITING_FOR_APPROVAL,
                ResolutionRunState.ACTION_FAILED,
                now,
                now,
            )
        listOf(
            ResolutionRunEventType.RETRY_STARTED to ResolutionRunState.SUPERSEDED,
            ResolutionRunEventType.ESCALATION_REQUESTED to ResolutionRunState.ESCALATED,
        ).forEach { (type, toState) ->
            val last = RecordedRunTransition(runId, 2, type, failed.toState, toState, now, now)
            assertThatCode {
                checkRecordedRunTransitions(start, state(toState, 2, now), listOf(failed, last))
            }.doesNotThrowAnyException()
        }
        assertThatThrownBy { failed.copy(type = ResolutionRunEventType.OUTCOME_PROOF_ACCEPTED) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun success() =
        RecordedRunTransition(
            runId,
            1,
            ResolutionRunEventType.CAPABILITY_SUCCEEDED,
            ResolutionRunState.WAITING_FOR_APPROVAL,
            ResolutionRunState.VERIFYING,
            now,
            now,
        )

    private fun state(
        value: ResolutionRunState,
        version: Long,
        at: Instant,
    ) = ResolutionRunStateSnapshot(runId, value, version, at)
}
