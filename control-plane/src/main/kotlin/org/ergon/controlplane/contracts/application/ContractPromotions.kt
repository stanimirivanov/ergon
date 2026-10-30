package org.ergon.controlplane.contracts.application

import org.ergon.contracts.domain.ContractPromotionAuthority
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.evaluation.application.EligibilityRequest
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentCodec
import org.ergon.controlplane.evaluation.application.GateDocumentIdentity
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.evaluation.domain.SimulationArtifact
import java.time.Instant
import java.util.UUID

/** Separate trusted-machine attestation; recording this is not itself a promotion command. */
data class PromotionAuthorityIntent(
    val actorId: UUID,
    val sourceProvider: String,
    val sourceReference: String,
    val expiresAt: Instant,
)

data class PromotionAuthorityRegistration(
    val authority: ContractPromotionAuthority,
    val created: Boolean,
)

interface PromotionAuthorityRepository {
    fun attest(
        tenant: UUID,
        id: UUID,
        intent: PromotionAuthorityIntent,
    ): PromotionAuthorityRegistration

    fun find(
        tenant: UUID,
        id: UUID,
    ): ContractPromotionAuthority?

    /** Database-time selection after command/contract locks; no resolver authority is considered. */
    fun current(
        tenant: UUID,
        actor: UUID,
    ): ContractPromotionAuthority?
}

/** Explicit confirmation of the next governing suite; version zero means no binding yet. */
data class RequiredSuiteIntent(
    val expectedVersion: Long,
    val suite: SimulationArtifact,
    val rationale: String,
) {
    init {
        require(expectedVersion in 0 until Long.MAX_VALUE)
        requireRationale(rationale)
    }
}

/** All review coordinates are supplied explicitly; evidence is independently resolved and assessed by the server. */
data class ContractPromotionIntent(
    val expectedVersion: Long,
    val suite: SimulationArtifact,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val reports: List<SelectedEvaluationReport>,
    val rationale: String,
) {
    init {
        require(expectedVersion > 0)
        requireRationale(rationale)
        eligibility()
    }

    fun eligibility() = EligibilityRequest(contract, policy, reports)
}

data class RequiredSuiteBinding(
    val contractKey: String,
    val version: Long,
    val commandId: UUID,
    val intent: RequiredSuiteIntent,
    val actorId: UUID,
    val authorityId: UUID,
    val recordedAt: Instant,
)

data class ContractPromotionReceipt(
    val commandId: UUID,
    val intent: ContractPromotionIntent,
    val actorId: UUID,
    val authorityId: UUID,
    val promotedAt: Instant,
)

data class RequiredSuiteRegistration(
    val binding: RequiredSuiteBinding,
    val created: Boolean,
)

data class ContractPromotionRegistration(
    val receipt: ContractPromotionReceipt,
    val created: Boolean,
)

/** Both mutations acquire command and contract locks in that order, inside the application transaction. */
interface ContractPromotionRepository {
    fun lock(
        tenant: UUID,
        key: String,
        command: UUID,
        operation: String,
    )

    fun currentBinding(
        tenant: UUID,
        key: String,
    ): RequiredSuiteBinding?

    fun bindingByCommand(
        tenant: UUID,
        command: UUID,
    ): RequiredSuiteBinding?

    fun promotion(
        tenant: UUID,
        command: UUID,
    ): ContractPromotionReceipt?

    fun appendBinding(
        tenant: UUID,
        key: String,
        command: UUID,
        intent: RequiredSuiteIntent,
        authority: ContractPromotionAuthority,
    ): RequiredSuiteBinding

    fun appendPromotion(
        tenant: UUID,
        command: UUID,
        intent: ContractPromotionIntent,
        authority: ContractPromotionAuthority,
    ): ContractPromotionReceipt
}

class PromotionUnavailable : RuntimeException()

class PromotionConflict : RuntimeException()

class PromotionIneligible : RuntimeException()

/** Explicit promotion is distinct from publication, case pinning and runtime admission. */
class ContractPromotions(
    private val transactions: TransactionRunner,
    private val repository: ContractPromotionRepository,
    private val authorities: PromotionAuthorityRepository,
    private val suites: EvaluationSuites,
    private val documents: GateDocumentCodec,
    private val gate: EvaluatePromotionEligibility,
) {
    fun bind(
        tenant: UUID,
        key: String,
        command: UUID,
        actor: UUID,
        intent: RequiredSuiteIntent,
    ): RequiredSuiteRegistration =
        transactions.required {
            ResolutionContractKey.of(key)
            repository.lock(tenant, key, command, "binding")
            val authority = currentAuthority(tenant, actor)
            val previous = repository.bindingByCommand(tenant, command)
            if (previous != null) {
                if (previous.contractKey != key || previous.intent != intent || previous.actorId != actor) {
                    throw PromotionConflict()
                }
                RequiredSuiteRegistration(previous, false)
            } else {
                val current = repository.currentBinding(tenant, key)
                if ((current?.version ?: 0) != intent.expectedVersion) throw PromotionConflict()
                val suiteIdentity = GateDocumentIdentity(tenant, intent.suite.key, intent.suite.revision)
                val stored = suites.get(GateDocumentKind.SUITE, suiteIdentity)
                if (stored.digest != intent.suite.sha256) throw PromotionConflict()
                val decoded = documents.decode(GateDocumentKind.SUITE, stored.definition.toByteArray())
                val suite = checkNotNull(decoded.suite)
                if (suite.contract.key != key) throw PromotionConflict()
                RequiredSuiteRegistration(repository.appendBinding(tenant, key, command, intent, authority), true)
            }
        }

    fun promote(
        tenant: UUID,
        command: UUID,
        actor: UUID,
        intent: ContractPromotionIntent,
    ): ContractPromotionRegistration =
        transactions.required {
            ResolutionContractKey.of(intent.contract.key)
            repository.lock(tenant, intent.contract.key, command, "promotion")
            val authority = currentAuthority(tenant, actor)
            val previous = repository.promotion(tenant, command)
            if (previous != null) {
                if (previous.intent != intent || previous.actorId != actor) throw PromotionConflict()
                ContractPromotionRegistration(previous, false)
            } else {
                val binding = repository.currentBinding(tenant, intent.contract.key) ?: throw PromotionConflict()
                if (binding.version != intent.expectedVersion || binding.intent.suite != intent.suite) {
                    throw PromotionConflict()
                }
                val identity = GateDocumentIdentity(tenant, intent.suite.key, intent.suite.revision)
                val assessment = gate.assess(identity, intent.eligibility())
                if (assessment.suite != intent.suite || !assessment.result.eligible) throw PromotionIneligible()
                ContractPromotionRegistration(repository.appendPromotion(tenant, command, intent, authority), true)
            }
        }

    fun current(
        tenant: UUID,
        key: String,
    ): RequiredSuiteBinding = repository.currentBinding(tenant, key) ?: throw PromotionUnavailable()

    fun get(
        tenant: UUID,
        command: UUID,
    ): ContractPromotionReceipt = repository.promotion(tenant, command) ?: throw PromotionUnavailable()

    private fun currentAuthority(
        tenant: UUID,
        actor: UUID,
    ) = authorities.current(tenant, actor) ?: throw PromotionUnavailable()
}

private const val MAX_RATIONALE = 2_000

private fun requireRationale(value: String) {
    require(value.isNotBlank() && value == value.trim() && value.length <= MAX_RATIONALE)
    require(value.none { it.isISOControl() && it != '\n' && it != '\t' })
    require(Charsets.UTF_8.newEncoder().canEncode(value))
}
