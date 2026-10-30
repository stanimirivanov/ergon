package org.ergon.controlplane.evaluation.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ContractPromotionIntent
import org.ergon.controlplane.contracts.application.ContractPromotions
import org.ergon.evaluation.domain.PromotionEligibility
import org.ergon.evaluation.domain.SimulationArtifact
import java.time.Instant
import java.util.UUID

data class SimulationPromotionReview(
    val intent: ContractPromotionIntent,
    val eligibility: PromotionEligibility,
)

data class SimulationPromotionReceipt(
    val commandId: UUID,
    val created: Boolean,
    val contract: SimulationArtifact,
    val suite: SimulationArtifact,
    val bindingVersion: Long,
    val promotedAt: Instant,
)

class SimulationLabPromotions(
    private val authority: SimulationLabAuthority,
    private val transactions: TransactionRunner,
    private val promotions: ContractPromotions,
    private val suites: EvaluationSuites,
    private val documents: GateDocumentCodec,
    private val eligibility: EvaluatePromotionEligibility,
) {
    /** Server selects the governing requirements; the browser explicitly selects exact report evidence. */
    fun review(
        tenant: UUID,
        actor: UUID,
        key: String,
        reports: List<SelectedEvaluationReport>,
        rationale: String,
    ): SimulationPromotionReview {
        requireViewer(authority, tenant, actor)
        val binding = promotions.current(tenant, key)
        val identity = GateDocumentIdentity(tenant, binding.intent.suite.key, binding.intent.suite.revision)
        val stored = suites.get(GateDocumentKind.SUITE, identity)
        val suite =
            checkNotNull(
                documents
                    .decode(
                        GateDocumentKind.SUITE,
                        stored.definition.toByteArray(),
                    ).suite,
            )
        val ordered =
            java.util.List.copyOf(
                reports.sortedWith(
                    compareBy(
                        { it.fixture.key },
                        { it.fixture.revision },
                    ),
                ),
            )
        val intent =
            ContractPromotionIntent(
                binding.version,
                binding.intent.suite,
                suite.contract,
                suite.policy,
                ordered,
                rationale,
            )
        return SimulationPromotionReview(intent, eligibility.assess(identity, intent.eligibility()).result)
    }

    /** The final viewer check uses PR10's retained actor lock, after command and contract locking. */
    fun confirm(
        tenant: UUID,
        actor: UUID,
        command: UUID,
        intent: ContractPromotionIntent,
    ): SimulationPromotionReceipt =
        transactions.required {
            requireViewer(authority, tenant, actor)
            val result = promotions.promote(tenant, command, actor, intent)
            // A revocation that won while promotion waited must roll back every newly written receipt/link.
            // Exact retries likewise require current viewer AND promoter evidence.
            requireViewer(authority, tenant, actor)
            val receipt = result.receipt
            SimulationPromotionReceipt(
                command,
                result.created,
                receipt.intent.contract,
                receipt.intent.suite,
                receipt.intent.expectedVersion,
                receipt.promotedAt,
            )
        }
}
