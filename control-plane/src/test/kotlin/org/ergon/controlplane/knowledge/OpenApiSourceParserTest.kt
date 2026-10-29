package org.ergon.controlplane.knowledge

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.knowledge.adapter.inbound.document.OpenApiSourceParser
import org.ergon.controlplane.knowledge.application.InvalidSource
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OpenApiSourceParserTest {
    private val parser = OpenApiSourceParser()
    private val tenant = TenantId(UUID(0, 1))
    private val id = SourceId(UUID(0, 2))

    @Test
    fun `original JSON citations preserve Unicode CRLF pointers and referenced locations`() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/knowledge/openapi.json"))
        val text = resource.use { it.reader(Charsets.UTF_8).readText() }.replace("\n", "\r\n")
        val source = source(text)
        val result = parser.parse(source)
        assertThat(result).isEqualTo(parser.parse(source))
        assertThat(result.observations.map { it.kind }).contains(
            SourceObservationKind.API_OPERATION,
            SourceObservationKind.API_PARAMETER,
            SourceObservationKind.API_RESPONSE,
            SourceObservationKind.API_DESCRIPTION,
        )
        val parameter = result.observations.first { it.kind == SourceObservationKind.API_PARAMETER }
        assertThat(parameter.jsonPointer).isEqualTo("/paths/~1accounts~1{id}/get/parameters/0")
        assertThat(parameter.citation.quote).isEqualTo("{\"\$ref\": \"#/components/parameters/Id\"}")
        assertThat(
            result.observations
                .first()
                .citation.quote,
        ).isEqualTo("\"Keep é exact\"")
        assertThat(result.observations.all { it.citation.verifyAgainst(source) }).isTrue()
        assertThat(
            result.observations
                .first { it.kind == SourceObservationKind.API_OPERATION }
                .citation.quote,
        ).contains("\r\n")
        val foreign = EvidenceSource.fromUtf8(TenantId(UUID(0, 3)), id, source.format, text.toByteArray())
        assertThat(parser.parse(foreign).extraction.id).isNotEqualTo(result.extraction.id)
    }

    @Test
    fun `malformed duplicate unsupported and invalid descriptions fail closed`() {
        for (text in listOf(
            "",
            "{} {}",
            "{broken",
            document("", "3.1.0"),
            document("", "2.0"),
            document("\"openapi\":\"3.0.3\","),
            document("\"x-test\":{\"same\":1,\"same\":2},"),
            "{\"openapi\":\"3.0.3\",\"paths\":{}}",
        )) {
            reject(text)
        }
        val markdown = EvidenceSource.fromUtf8(tenant, id, EvidenceSourceFormat.COMMONMARK, byteArrayOf())
        assertThatThrownBy { parser.parse(markdown) }.isInstanceOf(InvalidSource::class.java)
    }

    @Test
    fun `external and file references never reach a network resolver`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            for (reference in listOf(
                "http://127.0.0.1:${server.address.port}/schema.json",
                "file:///etc/passwd",
                "../private.json",
                "//host/schema",
                "#/%61",
                "#/missing",
                "#/bad~2escape",
            )) {
                reject(document("\"x-test\":{\"\$ref\":\"$reference\"},"))
            }
            assertThat(requests.get()).isZero()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cycles and depth expansion limits reject even unused components`() {
        reject(document("\"x-a\":{\"\$ref\":\"#/x-a\"},"))
        reject(document("\"x-a\":{\"\$ref\":\"#/x-b\"},\"x-b\":{\"\$ref\":\"#/x-a\"},"))
        val chain = (0..17).joinToString(",") { "\"x-$it\":{\"\$ref\":\"#/x-${it + 1}\"}" }
        reject(document("$chain,\"x-18\":{},"))
        reject(document("\"x-deep\":" + "[".repeat(33) + "0" + "]".repeat(33) + ","))
    }

    @Test
    fun `source node reference and observation budgets reject without partial output`() {
        reject(document("\"x-large\":\"" + "x".repeat(65_537) + "\","))
        reject(document("\"x-nodes\":[" + List(4097) { "0" }.joinToString(",") + "],"))
        val refs = (0..128).joinToString(",") { "{\"\$ref\":\"#/info\"}" }
        reject(document("\"x-refs\":[$refs],"))
        val descriptions = (0..256).joinToString(",") { "\"x-$it\":{\"description\":\"text\"}" }
        reject(document("$descriptions,"))
        val largeDescription = "z".repeat(45_000)
        val oversizedQuotes =
            """
            {"openapi":"3.0.3","info":{"title":"Test","version":"1"},
             "paths":{"/test":{"get":{"responses":{"200":{"description":"$largeDescription"}}}}}}
            """.trimIndent()
        reject(oversizedQuotes)
    }

    private fun document(
        extra: String,
        version: String = "3.0.3",
    ): String = "{$extra\"openapi\":\"$version\",\"info\":{\"title\":\"Test\",\"version\":\"1\"},\"paths\":{}}"

    private fun source(text: String): EvidenceSource =
        EvidenceSource.fromUtf8(tenant, id, EvidenceSourceFormat.OPENAPI_3_0_JSON, text.toByteArray(Charsets.UTF_8))

    private fun reject(text: String) {
        assertThatThrownBy { parser.parse(source(text)) }.isInstanceOf(InvalidSource::class.java)
    }
}
