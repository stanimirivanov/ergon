package org.ergon.contracts.domain

private const val CONTRACT_KEY_PATTERN = "[a-z0-9][a-z0-9-]{0,99}"
private const val QUALIFIED_NAME_PATTERN = "[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+"
private const val STEP_ID_PATTERN = "[a-z0-9][a-z0-9-]{0,99}"
private const val MAX_QUALIFIED_NAME_LENGTH = 200
private const val MAX_FACT_VALUE_LENGTH = 200

/** Stable logical identity shared by all revisions of one resolution contract. */
@JvmInline
value class ResolutionContractKey private constructor(
    val value: String,
) {
    companion object {
        /**
         * Creates a lowercase, URL-safe key without silently normalizing it.
         *
         * @throws IllegalArgumentException when [value] is not 1–100 lowercase
         *   letters, digits, or hyphens, beginning with a letter or digit.
         */
        fun of(value: String): ResolutionContractKey {
            require(value.matches(Regex(CONTRACT_KEY_PATTERN))) {
                "contract key must match $CONTRACT_KEY_PATTERN"
            }
            return ResolutionContractKey(value)
        }
    }
}

/** Positive revision identity within a [ResolutionContractKey]. */
@JvmInline
value class ResolutionContractRevision private constructor(
    val value: Int,
) {
    companion object {
        /** @throws IllegalArgumentException when [value] is not positive. */
        fun of(value: Int): ResolutionContractRevision {
            require(value > 0) { "contract revision must be positive" }
            return ResolutionContractRevision(value)
        }
    }
}

/** Immutable key and revision pair used to identify one exact contract definition. */
data class ResolutionContractIdentity(
    val key: ResolutionContractKey,
    val revision: ResolutionContractRevision,
)

/** Qualified semantic fact name referenced by contract conditions. */
@JvmInline
value class ContractFactType private constructor(
    val value: String,
) {
    companion object {
        /**
         * Creates a qualified lowercase name such as `account.access.state`.
         *
         * @throws IllegalArgumentException when [value] has fewer than two
         *   dot-separated segments or contains unsupported characters.
         */
        fun of(value: String): ContractFactType {
            require(value.length <= MAX_QUALIFIED_NAME_LENGTH && value.matches(Regex(QUALIFIED_NAME_PATTERN))) {
                "fact type must be a qualified lowercase name of at most $MAX_QUALIFIED_NAME_LENGTH characters"
            }
            return ContractFactType(value)
        }
    }
}

/** Opaque, non-blank expected value used by an equality condition. */
@JvmInline
value class ContractFactValue private constructor(
    val value: String,
) {
    companion object {
        /**
         * Preserves [value] exactly so matching semantics cannot change through normalization.
         *
         * @throws IllegalArgumentException when [value] is blank or longer than 200 characters.
         */
        fun of(value: String): ContractFactValue {
            require(value.isNotBlank() && value.length <= MAX_FACT_VALUE_LENGTH) {
                "fact value must be non-blank and at most $MAX_FACT_VALUE_LENGTH characters"
            }
            return ContractFactValue(value)
        }
    }
}

/** Declares the fact equality that must hold at a contract decision point. */
data class FactCondition(
    val fact: ContractFactType,
    val expectedValue: ContractFactValue,
)

/** Stable identity of one ordered step within a contract revision. */
@JvmInline
value class ResolutionStepId private constructor(
    val value: String,
) {
    companion object {
        /** @throws IllegalArgumentException when [value] is not a lowercase, URL-safe identifier. */
        fun of(value: String): ResolutionStepId {
            require(value.matches(Regex(STEP_ID_PATTERN))) {
                "step id must match $STEP_ID_PATTERN"
            }
            return ResolutionStepId(value)
        }
    }
}

/** Qualified capability requested by a step; it never grants that capability. */
@JvmInline
value class CapabilityName private constructor(
    val value: String,
) {
    companion object {
        /** @throws IllegalArgumentException when [value] is not a qualified lowercase name. */
        fun of(value: String): CapabilityName {
            require(value.length <= MAX_QUALIFIED_NAME_LENGTH && value.matches(Regex(QUALIFIED_NAME_PATTERN))) {
                "capability must be a qualified lowercase name of at most $MAX_QUALIFIED_NAME_LENGTH characters"
            }
            return CapabilityName(value)
        }
    }
}

/** Contract-declared action risk; policy may classify the action more strictly at runtime. */
enum class StepRisk {
    LOW,
    MEDIUM,
    HIGH,
}

/** Minimum human approval declared by a contract; runtime policy may require more authority. */
enum class ApprovalRequirement {
    NONE,
    REQUESTER,
    RESOLVER,
}

/**
 * One deterministic action request in contract order.
 *
 * Declaring [capability] is not authorization. A runtime must still evaluate
 * actor, tenant, policy, evidence, and risk before performing the action.
 * High-risk steps cannot declare [ApprovalRequirement.NONE].
 *
 * @throws IllegalArgumentException when a high-risk step has no approval requirement.
 */
data class ResolutionStep(
    val id: ResolutionStepId,
    val capability: CapabilityName,
    val risk: StepRisk,
    val approval: ApprovalRequirement,
) {
    init {
        require(risk != StepRisk.HIGH || approval != ApprovalRequirement.NONE) {
            "high-risk step requires requester or resolver approval"
        }
    }
}

/**
 * Immutable, ordered resolution instructions validated independently of their wire format.
 *
 * [applicability] must reference one of [requiredEvidence]. Step identities and
 * evidence names are unique. Lists are bounded to keep later validation and
 * execution deterministic under untrusted input.
 */
class ResolutionContract private constructor(
    val key: ResolutionContractKey,
    val revision: ResolutionContractRevision,
    val applicability: FactCondition,
    val requiredEvidence: List<ContractFactType>,
    val steps: List<ResolutionStep>,
    val outcomeProof: FactCondition,
) {
    companion object {
        const val MAX_REQUIRED_EVIDENCE = 50
        const val MAX_STEPS = 50

        /**
         * Defines a contract after enforcing cross-field invariants.
         *
         * Returned collections are immutable snapshots of the supplied lists.
         *
         * @throws IllegalArgumentException when evidence or steps are empty,
         *   exceed their bounds, contain duplicate identities, or omit the
         *   applicability fact from required evidence.
         */
        fun define(
            identity: ResolutionContractIdentity,
            applicability: FactCondition,
            requiredEvidence: List<ContractFactType>,
            steps: List<ResolutionStep>,
            outcomeProof: FactCondition,
        ): ResolutionContract {
            require(requiredEvidence.isNotEmpty()) { "required evidence must not be empty" }
            require(requiredEvidence.size <= MAX_REQUIRED_EVIDENCE) {
                "required evidence must not exceed $MAX_REQUIRED_EVIDENCE entries"
            }
            require(requiredEvidence.distinct().size == requiredEvidence.size) {
                "required evidence must not contain duplicates"
            }
            require(applicability.fact in requiredEvidence) {
                "applicability fact must be declared as required evidence"
            }
            require(steps.isNotEmpty()) { "contract steps must not be empty" }
            require(steps.size <= MAX_STEPS) { "contract steps must not exceed $MAX_STEPS entries" }
            require(steps.map(ResolutionStep::id).distinct().size == steps.size) {
                "contract step ids must be unique"
            }
            return ResolutionContract(
                key = identity.key,
                revision = identity.revision,
                applicability = applicability,
                requiredEvidence = requiredEvidence.toList(),
                steps = steps.toList(),
                outcomeProof = outcomeProof,
            )
        }
    }
}
