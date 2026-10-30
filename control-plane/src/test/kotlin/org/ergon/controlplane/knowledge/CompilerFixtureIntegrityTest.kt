package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.InvalidProcedure
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkProcedureOutline
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkSourceParser
import org.ergon.controlplane.knowledge.adapter.inbound.document.OpenApiSourceParser
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class CompilerFixtureIntegrityTest {
    private val tenant = TenantId(UUID.randomUUID())

    @Test
    fun `pinned upstream and adapted bytes retain their declared digests`() {
        CompilerAcceptanceFixtures
            .bytes("SHA256SUMS")
            .toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val (expected, name) = entry.split("  ", limit = 2)
                val digest = MessageDigest.getInstance("SHA-256").digest(CompilerAcceptanceFixtures.bytes(name))
                assertThat(HexFormat.of().formatHex(digest)).describedAs(name).isEqualTo(expected)
            }
    }

    @Test
    fun `real Markdown and API citations round trip offline and altered quotations are rejected`() {
        for (name in listOf("petstore-readme.md", "review-runbook.md", "petstore.json")) {
            val source = CompilerAcceptanceFixtures.source(tenant, name)
            val parser = if (name.endsWith(".json")) OpenApiSourceParser() else CommonMarkSourceParser()
            val compiled = parser.parse(source)
            assertThat(compiled.observations).isNotEmpty()
            assertThat(parser.parse(source)).isEqualTo(compiled)
            for (observation in compiled.observations) {
                assertThat(observation.citation.verifyAgainst(source)).isTrue()
                val altered = observation.citation.copy(quote = "invented uncited assertion")
                assertThat(altered.verifyAgainst(source)).isFalse()
            }
            if (name.endsWith(".json")) {
                assertThat(compiled.observations.mapNotNull { it.jsonPointer }).contains("/paths/~1pets/get")
            }
        }
    }

    @Test
    fun `ordered training procedure has exact step citations and cannot accept a forged selected quotation`() {
        val source = CompilerAcceptanceFixtures.source(tenant, "review-runbook.md")
        val list =
            CommonMarkSourceParser().parse(source).observations.single {
                it.kind == SourceObservationKind.ORDERED_LIST
            }
        val outline = CommonMarkProcedureOutline()
        assertThat(outline.steps(source, list.citation)).hasSize(2).allMatch { it.verifyAgainst(source) }
        assertThatThrownBy { outline.steps(source, list.citation.copy(quote = "uncited procedure")) }
            .isInstanceOf(InvalidProcedure::class.java)
    }
}
