package org.ergon.controlplane.contracts.adapter.inbound.document

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.contracts.application.ContractDocumentViolation
import org.ergon.controlplane.contracts.application.DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA
import org.ergon.controlplane.contracts.application.InvalidResolutionContractDocumentException
import org.ergon.controlplane.contracts.application.RESOLUTION_CONTRACT_DOCUMENT_SCHEMA
import org.ergon.controlplane.contracts.application.ResolutionContractDocumentDecoder
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.util.UUID

private const val MAX_CODE_POINTS = 50_000
private const val MAX_NESTING_DEPTH = 20
private val ROOT_KEYS =
    setOf("schema", "key", "revision", "applicability", "requiredEvidence", "steps", "outcomeProof")
private val DOCUMENTARY_KEYS = setOf("bundleId", "sha256")
private val CONDITION_KEYS = setOf("fact", "equals")
private val STEP_KEYS = setOf("id", "capability", "risk", "approval")

/** Strict version-dispatched YAML decoder; old documents cannot carry documentary semantics. */
@Component
class SnakeYamlResolutionContractDocumentDecoder : ResolutionContractDocumentDecoder {
    override fun decode(document: String): ResolutionContract {
        if (document.isBlank()) invalid("$", "document must not be blank")
        val root =
            try {
                newYamlReader().load<Any>(document)
            } catch (_: YAMLException) {
                invalid("$", "document is not valid, unambiguous YAML")
            }
        val contract = root.stringKeyMap("$")
        val schema = contract.requireSchema()
        val keys =
            if (schema == DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA) {
                ROOT_KEYS + "documentaryEvidence"
            } else {
                ROOT_KEYS
            }
        contract.requireKeys(keys, "$")
        val documentary =
            if (schema == DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA) {
                contract.documentaryRequirements()
            } else {
                emptyList()
            }

        val key = domainValue("$.key") { ResolutionContractKey.of(contract.string("key", "$")) }
        val revision =
            domainValue("$.revision") {
                ResolutionContractRevision.of(contract.integer("revision", "$"))
            }
        val requiredEvidence =
            contract.stringList("requiredEvidence").mapIndexed { index, value ->
                domainValue("$.requiredEvidence[$index]") { ContractFactType.of(value) }
            }
        val steps = contract.objectList("steps").mapIndexed(::step)

        return domainValue("$") {
            ResolutionContract
                .define(
                    identity = ResolutionContractIdentity(key, revision),
                    applicability = contract.condition("applicability"),
                    requiredEvidence = requiredEvidence,
                    steps = steps,
                    outcomeProof = contract.condition("outcomeProof"),
                ).let { if (documentary.isEmpty()) it else it.requiringDocumentaryEvidence(documentary) }
        }
    }

    // SnakeYAML readers are not thread-safe, so concurrent HTTP requests must not share one.
    private fun newYamlReader() =
        Yaml(
            SafeConstructor(
                LoaderOptions().apply {
                    isAllowDuplicateKeys = false
                    maxAliasesForCollections = 0
                    nestingDepthLimit = MAX_NESTING_DEPTH
                    codePointLimit = MAX_CODE_POINTS
                },
            ),
        )

    private fun Map<String, Any>.requireSchema(): String {
        val schema = string("schema", "$")
        if (schema !in setOf(RESOLUTION_CONTRACT_DOCUMENT_SCHEMA, DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA)) {
            invalid("$.schema", "unsupported resolution contract schema")
        }
        return schema
    }

    private fun Map<String, Any>.documentaryRequirements(): List<DocumentaryBundleRequirement> {
        val values = objectList("documentaryEvidence")
        if (values.size !in 1..ResolutionContract.MAX_DOCUMENTARY_EVIDENCE) {
            invalid("$.documentaryEvidence", "must contain 1–20 exact documentary bundle requirements")
        }
        return values.mapIndexed { index, item ->
            val path = "$.documentaryEvidence[$index]"
            item.requireKeys(DOCUMENTARY_KEYS, path)
            domainValue(path) {
                val id = item.string("bundleId", path)
                val parsed = UUID.fromString(id)
                require(parsed.toString() == id) { "bundleId must be a canonical lowercase UUID" }
                DocumentaryBundleRequirement(parsed, item.string("sha256", path))
            }
        }
    }

    private fun Map<String, Any>.condition(field: String): FactCondition {
        val path = "$.$field"
        val condition = required(field, "$").stringKeyMap(path)
        condition.requireKeys(CONDITION_KEYS, path)
        val fact = domainValue("$path.fact") { ContractFactType.of(condition.string("fact", path)) }
        val expectedValue =
            domainValue("$path.equals") {
                ContractFactValue.of(condition.string("equals", path))
            }
        return FactCondition(fact, expectedValue)
    }

    private fun step(
        index: Int,
        value: Map<String, Any>,
    ): ResolutionStep {
        val path = "$.steps[$index]"
        value.requireKeys(STEP_KEYS, path)
        val id = domainValue("$path.id") { ResolutionStepId.of(value.string("id", path)) }
        val capability =
            domainValue("$path.capability") {
                CapabilityName.of(value.string("capability", path))
            }
        return domainValue(path) {
            ResolutionStep(
                id = id,
                capability = capability,
                risk = value.enum<StepRisk>("risk", path),
                approval = value.enum<ApprovalRequirement>("approval", path),
            )
        }
    }
}

private fun Any?.stringKeyMap(path: String): Map<String, Any> {
    if (this !is Map<*, *>) invalid(path, "must be an object")
    val result = linkedMapOf<String, Any>()
    for ((key, value) in this) {
        if (key !is String) invalid(path, "field names must be strings")
        if (value == null) invalid("$path.$key", "must not be null")
        result[key] = value
    }
    return result
}

private fun Map<String, Any>.requireKeys(
    expected: Set<String>,
    path: String,
) {
    val unknown = keys - expected
    if (unknown.isNotEmpty()) invalid("$path.${unknown.first()}", "field is not supported")
    val missing = expected - keys
    if (missing.isNotEmpty()) invalid("$path.${missing.first()}", "field is required")
}

private fun Map<String, Any>.required(
    field: String,
    path: String,
): Any = this[field] ?: invalid("$path.$field", "field is required")

private fun Map<String, Any>.string(
    field: String,
    path: String,
): String = required(field, path) as? String ?: invalid("$path.$field", "must be a string")

private fun Map<String, Any>.integer(
    field: String,
    path: String,
): Int = required(field, path) as? Int ?: invalid("$path.$field", "must be an integer")

private fun Map<String, Any>.stringList(field: String): List<String> {
    val path = "$.$field"
    val values = required(field, "$") as? List<*> ?: invalid(path, "must be a list")
    return values.mapIndexed { index, value ->
        value as? String ?: invalid("$path[$index]", "must be a string")
    }
}

private fun Map<String, Any>.objectList(field: String): List<Map<String, Any>> {
    val path = "$.$field"
    val values = required(field, "$") as? List<*> ?: invalid(path, "must be a list")
    return values.mapIndexed { index, value -> value.stringKeyMap("$path[$index]") }
}

private inline fun <reified T : Enum<T>> Map<String, Any>.enum(
    field: String,
    path: String,
): T {
    val value = string(field, path)
    return enumValues<T>().firstOrNull { it.name == value }
        ?: invalid("$path.$field", "must be one of ${enumValues<T>().joinToString { it.name }}")
}

private fun <T> domainValue(
    path: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (exception: IllegalArgumentException) {
        invalid(path, exception.message ?: "value violates the contract domain")
    }

private fun invalid(
    path: String,
    message: String,
): Nothing =
    throw InvalidResolutionContractDocumentException(
        listOf(ContractDocumentViolation(path, message)),
    )
