package org.ergon.controlplane.contracts.application

import org.ergon.contracts.domain.ResolutionContract

/** Compatibility identifier required by the first resolution-contract document schema. */
const val RESOLUTION_CONTRACT_DOCUMENT_SCHEMA = "ergon.dev/resolution-contract/v1alpha1"

/** Version adding required exact documentary manifests; v1alpha1 remains operational-only. */
const val DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA = "ergon.dev/resolution-contract/v1alpha2"

/** Native contracts without documentary requirements retain the original normalized schema. */
fun ResolutionContract.documentSchema(): String =
    if (documentaryEvidence.isEmpty()) RESOLUTION_CONTRACT_DOCUMENT_SCHEMA else DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA

/** Decodes one external contract document into a fully validated domain contract. */
fun interface ResolutionContractDocumentDecoder {
    /**
     * Decodes untrusted [document] without applying, storing, or authorizing it.
     *
     * @throws InvalidResolutionContractDocumentException when syntax, shape,
     *   values, or cross-field invariants are invalid.
     */
    fun decode(document: String): ResolutionContract
}

/** One stable, client-correctable problem in a resolution contract document. */
data class ContractDocumentViolation(
    val path: String,
    val message: String,
)

/** Signals that a contract document cannot be safely interpreted. */
class InvalidResolutionContractDocumentException(
    val violations: List<ContractDocumentViolation>,
) : RuntimeException("Resolution contract document is invalid") {
    init {
        require(violations.isNotEmpty()) { "at least one contract violation is required" }
    }
}

/** Validates contract documents without persisting or executing their contents. */
class ResolutionContractValidationService(
    private val decoder: ResolutionContractDocumentDecoder,
) {
    /**
     * Returns the normalized domain meaning of [document].
     *
     * A successful result proves only that the document is well-formed and
     * internally consistent. It does not prove that referenced facts or
     * capabilities exist, and it grants no runtime authority.
     *
     * @throws InvalidResolutionContractDocumentException when validation fails.
     */
    fun validate(document: String): ResolutionContract = decoder.decode(document)
}
