package org.ergon.controlplane.evaluation.application

import org.ergon.contracts.domain.CapabilityName
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.controlplane.resolution.application.CapabilityInvocationCompletion
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import java.time.Duration
import java.util.UUID

/** Curated authority is simulation input only, never a live attestation. Offsets are relative to fixture evaluation. */
data class ScenarioAuthority(
    val actor: UUID,
    val authority: ApprovalAuthority,
    val caseId: UUID?,
    val fromSeconds: Long,
    val untilSeconds: Long,
)

data class ScenarioRoute(
    val capability: CapabilityName,
    val connector: ConnectorName,
)

data class ScenarioCall(
    val outcome: CapabilityInvocationCallOutcome,
    val elapsedNanoseconds: Long,
)

/** One approved-step scenario; repeated invocation calls reuse one consumption, not fresh successor runs. */
data class CapabilityScenario(
    val pinned: PinnedScenarioInput,
    val authority: ScenarioAuthority?,
    val decision: ApprovalDecisionOutcome?,
    val requestLifetime: Duration,
    val consumeAfterSeconds: Long,
    val route: ScenarioRoute?,
    val calls: List<ScenarioCall>,
    val invocationCount: Int,
    val proofCaseVersion: Long,
    val proof: List<ResolutionOutcomeEvidence>,
)

fun interface CapabilityScenarioDecoder {
    fun decode(bytes: ByteArray): CapabilityScenario
}

enum class CapabilityScenarioStatus {
    NOT_READY,
    POLICY_DENIED,
    WAITING_FOR_APPROVAL,
    APPROVAL_REJECTED,
    AUTHORITY_REJECTED,
    ROUTE_UNAVAILABLE,
    AUTHORIZATION_REJECTED,
    INVOCATION_UNKNOWN,
    ACTION_FAILED,
    PROOF_PENDING,
    PROOF_ACCEPTED,
}

/** This trace cannot close a case or establish production authority. All durations and identities are simulated. */
data class CapabilityScenarioTrace(
    val fixtureDigest: String,
    val status: CapabilityScenarioStatus,
    val idempotencyKeys: List<UUID> = emptyList(),
    val attempts: List<CapabilityInvocationCompletion> = emptyList(),
    val receipt: CapabilityInvocationReceipt? = null,
    val proof: ResolutionOutcomeProofAssessment? = null,
)

sealed interface CapabilityScenarioResult {
    data class Evaluated(
        val trace: CapabilityScenarioTrace,
    ) : CapabilityScenarioResult

    data class Unsupported(
        val reason: String,
    ) : CapabilityScenarioResult
}

/** Consumed boundary for isolated scripted invocation, deliberately separate from any production gateway wiring. */
fun interface ScenarioInvocationRunner {
    fun execute(
        input: CapabilityScenario,
        consumption: org.ergon.resolution.domain.CapabilityAuthorizationConsumption,
    ): CapabilityScenarioTrace
}
