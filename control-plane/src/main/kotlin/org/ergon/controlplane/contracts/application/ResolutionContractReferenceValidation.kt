package org.ergon.controlplane.contracts.application

import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ResolutionContract

/**
 * Registry of stable semantic names that contract authors may reference.
 *
 * Registration means Ergon understands a fact's meaning or a capability's
 * operation contract. It does not prove that a tenant has a current source for
 * the fact, an installed connector, or authority to invoke the capability.
 */
interface ResolutionContractReferenceRegistry {
    /** @return whether [fact] has a registered semantic definition. */
    fun containsFact(fact: ContractFactType): Boolean

    /** @return whether [capability] has a registered operation definition. */
    fun containsCapability(capability: CapabilityName): Boolean
}

/** Signals that an otherwise valid document refers to unknown semantic names. */
class UnregisteredContractReferencesException(
    val violations: List<ContractDocumentViolation>,
) : RuntimeException("Resolution contract contains unregistered references") {
    init {
        require(violations.isNotEmpty()) { "at least one unregistered reference is required" }
    }
}

/** Checks every fact and capability occurrence while preserving its document path. */
class ResolutionContractReferenceValidator(
    private val registry: ResolutionContractReferenceRegistry,
) {
    /**
     * Verifies that every semantic name in [contract] is registered.
     *
     * All unknown occurrences are reported together so an author can correct a
     * document in one review cycle.
     *
     * @throws UnregisteredContractReferencesException when one or more fact
     *   types or capabilities are unknown.
     */
    fun validate(contract: ResolutionContract) {
        val violations =
            buildList {
                addUnknownFact("$.applicability.fact", contract.applicability.fact)
                contract.requiredEvidence.forEachIndexed { index, fact ->
                    addUnknownFact("$.requiredEvidence[$index]", fact)
                }
                contract.steps.forEachIndexed { index, step ->
                    if (!registry.containsCapability(step.capability)) {
                        add(
                            ContractDocumentViolation(
                                path = "$.steps[$index].capability",
                                message = "capability '${step.capability.value}' is not registered",
                            ),
                        )
                    }
                }
                addUnknownFact("$.outcomeProof.fact", contract.outcomeProof.fact)
            }
        if (violations.isNotEmpty()) {
            throw UnregisteredContractReferencesException(violations)
        }
    }

    private fun MutableList<ContractDocumentViolation>.addUnknownFact(
        path: String,
        fact: ContractFactType,
    ) {
        if (!registry.containsFact(fact)) {
            add(
                ContractDocumentViolation(
                    path = path,
                    message = "fact type '${fact.value}' is not registered",
                ),
            )
        }
    }
}
