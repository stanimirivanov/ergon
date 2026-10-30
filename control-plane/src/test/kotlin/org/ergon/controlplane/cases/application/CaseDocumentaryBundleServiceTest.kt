package org.ergon.controlplane.cases.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.DocumentaryBundlePinned
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.adapter.inbound.http.CaseDocumentaryBundleController
import org.ergon.controlplane.cases.adapter.inbound.http.CaseExceptionHandler
import org.ergon.controlplane.cases.adapter.out.persistence.CaseEventJsonCodec
import org.ergon.controlplane.evidence.EvidenceBundleFixtures
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class CaseDocumentaryBundleServiceTest {
    private val f = EvidenceBundleFixtures()
    private val tenant = f.f.source.tenant
    private val case = CaseId(UUID.randomUUID())
    private val reference = DocumentaryBundleRequirement(f.stored.command.id, f.stored.sha256)
    private val opened =
        CaseOpened(
            "Restore",
            UUID.randomUUID(),
            ObservationOriginType.REQUESTER,
            "api",
            null,
            "Locked",
            f.f.now,
        )
    private val saved = mutableListOf<CaseEvent>(opened)
    private val bundles = Mockito.mock(EvidenceBundleRepository::class.java)
    private val store =
        object : CaseEventStore {
            override fun load(
                tenantId: TenantId,
                caseId: CaseId,
            ): List<CaseEvent> = if (tenantId == tenant && caseId == case) saved.toList() else emptyList()

            override fun append(
                tenantId: TenantId,
                caseId: CaseId,
                expectedVersion: Long,
                events: List<NewCaseEvent>,
            ): List<StoredCaseEvent> {
                check(expectedVersion == saved.size.toLong())
                return events.mapIndexed { index, event ->
                    saved += event.event
                    StoredCaseEvent(event.eventId, expectedVersion + index + 1, event.event, f.f.now.plusSeconds(5))
                }
            }
        }
    private val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T = block()
        }
    private val projection =
        object : CaseProjectionWriter {
            override fun project(
                case: ErgonCase,
                events: List<StoredCaseEvent>,
            ) = Unit
        }
    private val service =
        CaseDocumentaryBundleService(
            store,
            bundles,
            CaseEventCommitter(store, projection, IdentityGenerator(UUID::randomUUID), transactions),
            Clock.fixed(f.f.now, ZoneOffset.UTC),
        )

    @Test
    fun `exact attachments advance the case and new event codecs preserve old stream decoding`() {
        Mockito.`when`(bundles.find(tenant, reference.bundleId)).thenReturn(f.stored)
        val result = service.pin(PinCaseDocumentaryBundleCommand(tenant.value, case.value, 1, reference))
        assertThat(result.streamVersion).isEqualTo(2)
        assertThat(saved.last()).isEqualTo(DocumentaryBundlePinned(reference.bundleId, reference.sha256, f.f.now))
        val codec = CaseEventJsonCodec(JsonMapper.builder().build())
        for (event in saved) {
            val encoded = codec.encode(event)
            assertThat(codec.decode(encoded.eventType, encoded.schemaVersion, encoded.payload)).isEqualTo(event)
        }
        assertThatThrownBy { service.pin(PinCaseDocumentaryBundleCommand(tenant.value, case.value, 2, reference)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(saved).hasSize(2)
    }

    @Test
    fun `stale missing foreign and digest mismatched requests never append`() {
        assertThatThrownBy { service.pin(PinCaseDocumentaryBundleCommand(tenant.value, case.value, 2, reference)) }
            .isInstanceOf(ConcurrentCaseModificationException::class.java)
        Mockito.verifyNoInteractions(bundles)
        assertThatThrownBy { service.pin(PinCaseDocumentaryBundleCommand(UUID.randomUUID(), case.value, 1, reference)) }
            .isInstanceOf(CaseNotFoundException::class.java)
        assertThatThrownBy { service.pin(PinCaseDocumentaryBundleCommand(tenant.value, case.value, 1, reference)) }
            .isInstanceOf(CaseDocumentaryBundleUnavailable::class.java)
        Mockito.`when`(bundles.find(tenant, reference.bundleId)).thenReturn(f.stored)
        assertThatThrownBy {
            service.pin(
                PinCaseDocumentaryBundleCommand(
                    tenant.value,
                    case.value,
                    1,
                    reference.copy(sha256 = "0".repeat(64)),
                ),
            )
        }.isInstanceOf(CaseDocumentaryBundleUnavailable::class.java)
        assertThat(saved).containsExactly(opened)
    }

    @Test
    fun `internal attachment API enforces version preconditions and returns safe failures`() {
        Mockito.`when`(bundles.find(tenant, reference.bundleId)).thenReturn(f.stored)
        val mvc =
            MockMvcBuilders
                .standaloneSetup(CaseDocumentaryBundleController(service))
                .setControllerAdvice(CaseExceptionHandler())
                .build()
        val path = "/internal/v1/tenants/${tenant.value}/cases/${case.value}/documentary-bundles"
        val body = """{"bundleId":"${reference.bundleId}","sha256":"${reference.sha256}"}"""
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isPreconditionRequired)
        mvc
            .perform(post(path).header("If-Match", "\"9\"").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isPreconditionFailed)
        mvc
            .perform(post(path).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.streamVersion").value(2))
    }
}
