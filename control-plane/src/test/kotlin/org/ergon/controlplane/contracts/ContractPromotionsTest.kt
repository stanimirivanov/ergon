package org.ergon.controlplane.contracts

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ContractPromotionAuthority
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.adapter.inbound.document.PromotionDocumentCodec
import org.ergon.controlplane.contracts.adapter.inbound.http.ContractPromotionController
import org.ergon.controlplane.contracts.application.ContractPromotionIntent
import org.ergon.controlplane.contracts.application.ContractPromotionReceipt
import org.ergon.controlplane.contracts.application.ContractPromotionRepository
import org.ergon.controlplane.contracts.application.ContractPromotions
import org.ergon.controlplane.contracts.application.PromotionAuthorityIntent
import org.ergon.controlplane.contracts.application.PromotionAuthorityRegistration
import org.ergon.controlplane.contracts.application.PromotionAuthorityRepository
import org.ergon.controlplane.contracts.application.PromotionConflict
import org.ergon.controlplane.contracts.application.PromotionIneligible
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.ergon.controlplane.contracts.application.RequiredSuiteBinding
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.ergon.controlplane.evaluation.SuiteHarness
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class ContractPromotionsTest {
    private val h = PromotionHarness()

    @Test
    fun `dedicated authority admits exact eligible promotion and retry retains original proof`() {
        val binding = h.bind()
        assertThat(binding.created).isTrue()
        val id = UUID.randomUUID()
        val result = h.service.promote(h.tenant, id, h.actor, h.intent())
        assertThat(result.created).isTrue()
        assertThat(result.receipt.authorityId).isEqualTo(h.authority.id)
        h.authority = h.authority.copy(id = UUID.randomUUID())
        val retry = h.service.promote(h.tenant, id, h.actor, h.intent())
        assertThat(retry.created).isFalse()
        assertThat(retry.receipt).isEqualTo(result.receipt)
        assertThatThrownBy { h.service.promote(h.tenant, id, h.actor, h.intent().copy(rationale = "changed")) }
            .isInstanceOf(PromotionConflict::class.java)
        assertThat(h.promotions).hasSize(1)
    }

    @Test
    fun `no authority expired after locking and foreign tenant cannot create binding or promotion`() {
        h.active = false
        assertThatThrownBy { h.bind() }.isInstanceOf(PromotionUnavailable::class.java)
        h.active = true
        h.bind()
        h.onLock = { h.active = false }
        assertThatThrownBy { h.service.promote(h.tenant, UUID.randomUUID(), h.actor, h.intent()) }
            .isInstanceOf(PromotionUnavailable::class.java)
        h.onLock = {}
        h.active = true
        assertThatThrownBy { h.service.promote(UUID.randomUUID(), UUID.randomUUID(), h.actor, h.intent()) }
            .isInstanceOf(PromotionUnavailable::class.java)
        assertThat(h.promotions).isEmpty()
    }

    @Test
    fun `changed required suite version rejects stale intent while old exact receipts remain historical`() {
        val bindingId = UUID.randomUUID()
        val first = h.service.bind(h.tenant, h.key, bindingId, h.actor, h.bindingIntent())
        val promotionId = UUID.randomUUID()
        val receipt = h.service.promote(h.tenant, promotionId, h.actor, h.intent()).receipt
        h.service.bind(h.tenant, h.key, UUID.randomUUID(), h.actor, h.bindingIntent().copy(expectedVersion = 1))
        assertThatThrownBy { h.service.promote(h.tenant, UUID.randomUUID(), h.actor, h.intent()) }
            .isInstanceOf(PromotionConflict::class.java)
        assertThat(h.service.promote(h.tenant, promotionId, h.actor, h.intent()).receipt).isEqualTo(receipt)
        assertThat(h.service.bind(h.tenant, h.key, bindingId, h.actor, h.bindingIntent()).binding)
            .isEqualTo(first.binding)
        assertThat(h.service.current(h.tenant, h.key).version).isEqualTo(2)
    }

    @Test
    fun `missing report changed digest and unbound suite never create promotion receipts`() {
        h.bind()
        val incomplete = h.intent().copy(reports = emptyList())
        assertThatThrownBy { h.service.promote(h.tenant, UUID.randomUUID(), h.actor, incomplete) }
            .isInstanceOf(PromotionIneligible::class.java)
        val changed = h.intent().copy(reports = listOf(h.base.selection().copy(reportDigest = "0".repeat(64))))
        assertThatThrownBy { h.service.promote(h.tenant, UUID.randomUUID(), h.actor, changed) }
            .isInstanceOf(PromotionIneligible::class.java)
        val wrongSuite = h.intent().copy(suite = h.suite.copy(sha256 = "0".repeat(64)))
        assertThatThrownBy { h.service.promote(h.tenant, UUID.randomUUID(), h.actor, wrongSuite) }
            .isInstanceOf(PromotionConflict::class.java)
        assertThat(h.promotions).isEmpty()
    }

    @Test
    fun `internal commands reject forged passed bodies and headers alone do not grant authority`() {
        val codec = PromotionDocumentCodec(h.base.f.pinned, h.base.f.mapper)
        val mvc = MockMvcBuilders.standaloneSetup(ContractPromotionController(h.service, h.authorities, codec)).build()
        val uri = "/internal/v1/tenants/${h.tenant}/contract-promotions"
        mvc
            .perform(
                post("$uri/requirements/${h.key}/commands/${UUID.randomUUID()}")
                    .header("X-Ergon-Actor-Id", h.actor)
                    .content(codec.encode(h.bindingIntent())),
            ).andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc
            .perform(
                post("$uri/commands/${UUID.randomUUID()}")
                    .header("X-Ergon-Actor-Id", h.actor)
                    .content(codec.encode(h.intent())),
            ).andExpect(status().isCreated)
        mvc
            .perform(
                post("$uri/commands/${UUID.randomUUID()}")
                    .header("X-Ergon-Actor-Id", h.actor)
                    .content("{\"passed\":true}"),
            ).andExpect(status().isUnprocessableEntity)
        h.active = false
        mvc
            .perform(
                post("$uri/commands/${UUID.randomUUID()}")
                    .header("X-Ergon-Actor-Id", h.actor)
                    .content(codec.encode(h.intent())),
            ).andExpect(status().isNotFound)
    }
}

internal class PromotionHarness {
    val base = SuiteHarness().also { it.register() }
    val tenant = base.tenant
    val actor = base.actor
    val key = base.fixture.fixture.contract.key
    val suite = base.stored.getValue(GateDocumentKind.SUITE to base.suiteId).reference()
    val at: Instant = Instant.parse("2026-10-30T00:00:00Z")
    var authority = ContractPromotionAuthority(UUID.randomUUID(), actor, "test", "authority", at, at.plusSeconds(60))
    var active = true
    var onLock: () -> Unit = {}
    val bindings = mutableMapOf<Pair<UUID, UUID>, RequiredSuiteBinding>()
    val promotions = mutableMapOf<Pair<UUID, UUID>, ContractPromotionReceipt>()
    val authorities =
        object : PromotionAuthorityRepository {
            override fun attest(
                tenant: UUID,
                id: UUID,
                intent: PromotionAuthorityIntent,
            ): PromotionAuthorityRegistration = error("unused")

            override fun find(
                tenant: UUID,
                id: UUID,
            ): ContractPromotionAuthority? =
                authority.takeIf {
                    tenant == this@PromotionHarness.tenant &&
                        id == it.id
                }

            override fun current(
                tenant: UUID,
                actor: UUID,
            ): ContractPromotionAuthority? =
                authority.takeIf {
                    active && tenant == this@PromotionHarness.tenant && actor == it.actorId
                }
        }
    val repository =
        object : ContractPromotionRepository {
            override fun lock(
                tenant: UUID,
                key: String,
                command: UUID,
                operation: String,
            ) {
                onLock()
            }

            override fun currentBinding(
                tenant: UUID,
                key: String,
            ) = bindings
                .filter { it.key.first == tenant && it.value.contractKey == key }
                .values
                .maxByOrNull { it.version }

            override fun bindingByCommand(
                tenant: UUID,
                command: UUID,
            ) = bindings[tenant to command]

            override fun promotion(
                tenant: UUID,
                command: UUID,
            ) = promotions[tenant to command]

            override fun appendBinding(
                tenant: UUID,
                key: String,
                command: UUID,
                intent: RequiredSuiteIntent,
                authority: ContractPromotionAuthority,
            ): RequiredSuiteBinding =
                RequiredSuiteBinding(
                    key,
                    intent.expectedVersion + 1,
                    command,
                    intent,
                    authority.actorId,
                    authority.id,
                    at,
                ).also { bindings[tenant to command] = it }

            override fun appendPromotion(
                tenant: UUID,
                command: UUID,
                intent: ContractPromotionIntent,
                authority: ContractPromotionAuthority,
            ): ContractPromotionReceipt =
                ContractPromotionReceipt(command, intent, authority.actorId, authority.id, at)
                    .also { promotions[tenant to command] = it }
        }
    val transactions =
        object : TransactionRunner {
            override fun <T : Any> required(block: () -> T): T = block()
        }
    val service = ContractPromotions(transactions, repository, authorities, base.suites, base.codec, base.gate)

    fun bindingIntent() = RequiredSuiteIntent(0, suite, "Confirm this governing suite")

    fun intent() =
        ContractPromotionIntent(
            1,
            suite,
            base.fixture.fixture.contract,
            base.policyRef(),
            listOf(base.selection()),
            "Reviewed evidence",
        )

    fun bind() = service.bind(tenant, key, UUID.randomUUID(), actor, bindingIntent())
}
