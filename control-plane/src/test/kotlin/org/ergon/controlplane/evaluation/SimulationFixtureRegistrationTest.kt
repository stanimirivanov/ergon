package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationFixtureJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.http.SimulationFixtureController
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.PreparedSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureRegistration
import org.ergon.controlplane.evaluation.application.SimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.SimulationFixtureService
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.StoredSimulationFixture
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class SimulationFixtureRegistrationTest {
    private val mapper = JsonMapper.builder().build()
    private val codec = SimulationFixtureJsonCodec(mapper)
    private val document = SimulationPackageFixtures.document()

    private fun bytes(value: Any = document) = mapper.writeValueAsBytes(value)

    @Test
    fun `normalization preserves exact identity and accepts equivalent material order`() {
        val prepared = codec.decode(bytes())
        assertThat(prepared.fixture.semanticDigest()).isEqualTo(document.digest)
        assertThat(codec.decode(bytes(document.copy(materials = document.materials.reversed()))).definition)
            .isEqualTo(prepared.definition)
        assertThat(codec.decode(prepared.definition.toByteArray()).definition).isEqualTo(prepared.definition)
    }

    @Test
    fun `rejects missing extra duplicate and corrupted material and mismatched manifest digest`() {
        val first = document.materials.first()
        val invalid =
            listOf(
                document.copy(materials = document.materials.drop(1)),
                document.copy(materials = document.materials + first),
                document.copy(materials = document.materials.drop(1) + document.materials.last()),
                document.copy(materials = document.materials.drop(1) + first.copy(content = "changed")),
                document.copy(digest = "0".repeat(64)),
                document.copy(provenance = document.provenance.copy(description = "")),
                document.copy(provenance = document.provenance.copy(description = "bad\u0000origin")),
                document.copy(provenance = document.provenance.copy(description = "bad\uD800origin")),
            )
        invalid.forEach { value ->
            assertThatThrownBy { codec.decode(bytes(value)) }.isInstanceOf(InvalidSimulationFixture::class.java)
        }
    }

    @Test
    fun `strict parser rejects unknown fields duplicates coercions versions malformed UTF8 and trailing input`() {
        val json = mapper.writeValueAsString(document)
        val invalid =
            listOf(
                "null",
                json.replace("\"scripts\":[]", "\"scripts\":[null]"),
                json.replace("\"documentaryPins\":[]", "\"documentaryPins\":[null]"),
                "{\"extra\":true," + json.drop(1),
                "{\"digest\":\"bad\"," + json.drop(1),
                json.replace("ergon.simulation/v1", "ergon.simulation/v2"),
                json.replace("\"streamVersion\":1", "\"streamVersion\":1.5"),
                json.replace("\"streamVersion\":1", "\"streamVersion\":\"1\""),
                json.replace("\"NOT_READY\"", "0"),
                json.replace("\"revision\":\"1\"", "\"revision\":1"),
                json + "{}",
            )
        invalid.forEach { value ->
            assertThatThrownBy { codec.decode(value.toByteArray()) }
                .describedAs(value)
                .isInstanceOf(InvalidSimulationFixture::class.java)
        }
        assertThatThrownBy { codec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        assertThatThrownBy { codec.decode(ByteArray(SimulationFixtureService.MAX_BYTES + 1)) }
            .isInstanceOf(SimulationFixtureTooLarge::class.java)
    }

    @Test
    fun `rejects null material entries and conflicting revisions`() {
        val json = mapper.writeValueAsString(document)
        val tree = mapper.readTree(json) as tools.jackson.databind.node.ObjectNode
        tree.putArray("materials").addNull()
        assertThatThrownBy { codec.decode(mapper.writeValueAsBytes(tree)) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        val bad = document.manifest.copy(policy = document.manifest.contract.copy(sha256 = "0".repeat(64)))
        val conflict = document.copy(manifest = bad, digest = bad.toDomain().semanticDigest())
        assertThatThrownBy { codec.decode(bytes(conflict)) }.isInstanceOf(InvalidSimulationFixture::class.java)
    }

    @Test
    fun `timestamps require strings and alternate offsets normalize to the same definition`() {
        val tree = mapper.readTree(bytes()) as tools.jackson.databind.node.ObjectNode
        (tree.get("manifest") as tools.jackson.databind.node.ObjectNode)
            .put("evaluationAt", Instant.parse(document.manifest.evaluationAt).epochSecond)
        assertThatThrownBy { codec.decode(mapper.writeValueAsBytes(tree)) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        assertThatThrownBy {
            codec.decode(bytes(document.copy(manifest = document.manifest.copy(evaluationAt = "bad"))))
        }.isInstanceOf(InvalidSimulationFixture::class.java)
        val alternate = document.copy(manifest = document.manifest.copy(evaluationAt = "2026-10-30T02:00:00+02:00"))
        assertThat(codec.decode(bytes(alternate)).definition).isEqualTo(codec.decode(bytes()).definition)
    }

    @Test
    fun `internal HTTP is bounded and rejects foreign manifest before repository access`() {
        var calls = 0
        val repository =
            object : SimulationFixtureRepository {
                override fun register(
                    identity: SimulationFixtureIdentity,
                    prepared: PreparedSimulationFixture,
                    actor: UUID,
                ): SimulationFixtureRegistration {
                    calls++
                    return SimulationFixtureRegistration(
                        StoredSimulationFixture(
                            identity,
                            prepared.fixture.semanticDigest(),
                            prepared.definition,
                            actor,
                            Instant.EPOCH,
                        ),
                        true,
                    )
                }

                override fun find(identity: SimulationFixtureIdentity): StoredSimulationFixture? = null
            }
        val service = SimulationFixtureService(codec, repository)
        val mvc = MockMvcBuilders.standaloneSetup(SimulationFixtureController(service)).build()
        val tenant = document.manifest.tenantId
        val path = "/internal/v1/tenants/$tenant/simulation-fixtures/sample/revisions/1"
        mvc
            .perform(
                post(path)
                    .header("X-Ergon-Actor-Id", UUID.randomUUID())
                    .contentType("application/json")
                    .content(bytes()),
            ).andExpect(status().isCreated)
        mvc
            .perform(
                post(path)
                    .header("X-Ergon-Actor-Id", UUID.randomUUID())
                    .contentType("application/json")
                    .content(bytes(SimulationPackageFixtures.document())),
            ).andExpect(status().isUnprocessableEntity)
        mvc
            .perform(
                post(path)
                    .header("X-Ergon-Actor-Id", UUID.randomUUID())
                    .contentType("application/json")
                    .content(ByteArray(SimulationFixtureService.MAX_BYTES + 1)),
            ).andExpect(status().isPayloadTooLarge)
        mvc.perform(get(path)).andExpect(status().isNotFound)
        assertThat(calls).isEqualTo(1)
    }
}
