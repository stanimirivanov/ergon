package org.ergon.evaluation.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SimulationFixtureTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val first = SimulationDocumentaryPin(artifact("bundle-a"), 2, at.minusSeconds(2))
    private val second = SimulationDocumentaryPin(artifact("bundle-b"), 3, at.minusSeconds(1))

    @Test
    fun `canonical identity matches independent binary encoding vector`() {
        assertThat(fixture().semanticDigest())
            .isEqualTo("1a126d53039c811a2745c07da59f5e214c4072f542ce7532a6b21265696a5241")
        assertThat(fixture().canonicalBytes()).isEqualTo(fixture().canonicalBytes())
    }

    @Test
    fun `documentary order normalizes but script execution order is semantic`() {
        assertThat(fixture(pins = listOf(first, second)).semanticDigest())
            .isEqualTo(fixture(pins = listOf(second, first)).semanticDigest())
        val scripts = listOf(script("call-a"), script("call-b"))
        assertThat(fixture(scripts = scripts).semanticDigest())
            .isNotEqualTo(fixture(scripts = scripts.reversed()).semanticDigest())
    }

    @Test
    fun `every pinned input and expected outcome contributes to identity`() {
        val baseline = fixture().semanticDigest()
        val variants =
            listOf(
                fixture(contract = artifact("other-contract")),
                fixture(policy = artifact("other-policy")),
                fixture(evaluator = artifact("other-evaluator")),
                fixture(assertions = artifact("other-assertions")),
                fixture(prefix = prefix().copy(streamVersion = 4)),
                fixture(prefix = prefix().copy(material = artifact("other-prefix"))),
                fixture(tenant = UUID(0, 3)),
                fixture(time = at.plusSeconds(1)),
                fixture(pins = listOf(first)),
                fixture(scripts = listOf(script("call"))),
                fixture(budget = artifact("budget")),
                fixture(outcome = SimulationExpectedOutcome.FAILED),
                fixture(contract = artifact("contract").copy(revision = "2")),
                fixture(contract = artifact("contract").copy(sha256 = "b".repeat(64))),
            )
        assertThat(variants.map { it.semanticDigest() }).doesNotContain(baseline).doesNotHaveDuplicates()
        assertThat(fixture(pins = listOf(first)).semanticDigest())
            .isNotEqualTo(fixture(pins = listOf(first.copy(attachedAt = at.minusSeconds(3)))).semanticDigest())
    }

    @Test
    fun `construction freezes caller collections and returned bytes`() {
        val pins = mutableListOf(first)
        val scripts = mutableListOf(script("call"))
        val value = fixture(pins = pins, scripts = scripts)
        val digest = value.semanticDigest()
        pins.clear()
        scripts.clear()
        value.canonicalBytes().fill(0)
        assertThat(value.documentaryPins).containsExactly(first)
        assertThat(value.scripts).hasSize(1)
        assertThat(value.semanticDigest()).isEqualTo(digest)
    }

    @Test
    fun `rejects unsupported versions malformed references and excessive input`() {
        assertThatIllegalArgumentException().isThrownBy { fixture(version = "ergon.simulation/v2") }
        assertThatIllegalArgumentException().isThrownBy { artifact("https://example.org/script") }
        assertThatIllegalArgumentException().isThrownBy { artifact("x".repeat(129)) }
        assertThatIllegalArgumentException().isThrownBy { artifact("x").copy(sha256 = "A".repeat(64)) }
        assertThatIllegalArgumentException().isThrownBy { artifact("x").copy(revision = "") }
        assertThatIllegalArgumentException().isThrownBy { fixture(scripts = List(101) { script("s$it") }) }
        assertThatIllegalArgumentException().isThrownBy {
            fixture(pins = List(21) { first.copy(bundle = artifact("b$it"), streamVersion = it + 1L) })
        }
        assertThat(fixture(scripts = List(100) { script("s$it") }).scripts).hasSize(100)
    }

    @Test
    fun `rejects ambiguous pins and timestamps rather than truncating history`() {
        assertThatIllegalArgumentException().isThrownBy { fixture(time = at.plusNanos(1)) }
        assertThatIllegalArgumentException().isThrownBy { first.copy(attachedAt = at.plusNanos(1)) }
        assertThatIllegalArgumentException().isThrownBy { fixture(pins = listOf(first, first)) }
        assertThatIllegalArgumentException().isThrownBy { fixture(pins = listOf(first.copy(streamVersion = 4))) }
        assertThatIllegalArgumentException().isThrownBy {
            fixture(pins = listOf(first.copy(attachedAt = at.plusSeconds(1))))
        }
        assertThatIllegalArgumentException().isThrownBy { prefix().copy(streamVersion = 0) }
        assertThatIllegalArgumentException().isThrownBy { fixture(scripts = listOf(script("same"), script("same"))) }
    }

    @Test
    fun `model scripts require exact configuration and unused configuration is rejected`() {
        val model = SimulationScript(SimulationScriptKind.MODEL, artifact("response"))
        assertThatIllegalArgumentException().isThrownBy { fixture(scripts = listOf(model)) }
        assertThatIllegalArgumentException().isThrownBy { fixture(model = artifact("configuration")) }
        val baseline = fixture(scripts = listOf(model), model = artifact("configuration"))
        assertThat(baseline.semanticDigest())
            .isNotEqualTo(fixture(scripts = listOf(model), model = artifact("other-configuration")).semanticDigest())
    }

    private fun artifact(key: String) = SimulationArtifact(key, "1", "a".repeat(64))

    private fun script(key: String) = SimulationScript(SimulationScriptKind.CONNECTOR, artifact(key))

    private fun prefix() = SimulationCasePrefix(UUID(0, 2), 3, artifact("prefix"))

    @Suppress("LongParameterList") // Named overrides make individual identity and validation cases explicit.
    private fun fixture(
        version: String = SimulationFixture.VERSION,
        tenant: UUID = UUID(0, 1),
        contract: SimulationArtifact = artifact("contract"),
        policy: SimulationArtifact = artifact("policy"),
        prefix: SimulationCasePrefix = prefix(),
        evaluator: SimulationArtifact = artifact("evaluator"),
        time: Instant = at,
        pins: List<SimulationDocumentaryPin> = emptyList(),
        scripts: List<SimulationScript> = emptyList(),
        model: SimulationArtifact? = null,
        assertions: SimulationArtifact = artifact("assertions"),
        outcome: SimulationExpectedOutcome = SimulationExpectedOutcome.VERIFIED,
        budget: SimulationArtifact? = null,
    ) = SimulationFixture(
        version,
        tenant,
        contract,
        policy,
        prefix,
        evaluator,
        time,
        pins,
        scripts,
        model,
        assertions,
        outcome,
        budget,
    )
}
