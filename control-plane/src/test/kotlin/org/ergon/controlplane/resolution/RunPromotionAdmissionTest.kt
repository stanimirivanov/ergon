package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.application.CurrentContractPromotion
import org.ergon.controlplane.resolution.adapter.inbound.http.ResolutionRunController
import org.ergon.controlplane.resolution.adapter.inbound.http.RunPromotionExceptionHandler
import org.ergon.controlplane.resolution.application.ResolutionRunPromotionRequiredException
import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class RunPromotionAdmissionTest {
    private val tenant = UUID.randomUUID()
    private val contract =
        ResolutionContractIdentity(
            ResolutionContractKey.of("sample"),
            ResolutionContractRevision.of(1),
        )

    @Test
    fun `empty scope and other tenants preserve admission without reading promotion storage`() {
        val query = CurrentContractPromotion { _, _ -> error("not enforced") }
        RunPromotionConfiguration().runPromotionAdmission(query, "").requireCurrent(tenant, contract)
        RunPromotionConfiguration()
            .runPromotionAdmission(query, UUID.randomUUID().toString())
            .requireCurrent(tenant, contract)
    }

    @Test
    fun `enforced scope checks exact identity and refuses absence without swallowing storage failures`() {
        var qualifying = false
        val query =
            CurrentContractPromotion { scoped, pinned ->
                assertThat(scoped).isEqualTo(tenant)
                assertThat(pinned).isEqualTo(contract)
                qualifying
            }
        val gate = RunPromotionConfiguration().runPromotionAdmission(query, " $tenant ")
        assertThatThrownBy { gate.requireCurrent(tenant, contract) }
            .isInstanceOf(ResolutionRunPromotionRequiredException::class.java)
        qualifying = true
        gate.requireCurrent(tenant, contract)
        val unavailable = CurrentContractPromotion { _, _ -> error("storage unavailable") }
        assertThatThrownBy {
            RunPromotionConfiguration()
                .runPromotionAdmission(unavailable, tenant.toString())
                .requireCurrent(tenant, contract)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `malformed scope fails startup instead of silently disabling enforcement`() {
        val query = CurrentContractPromotion { _, _ -> true }
        listOf("not-a-tenant", "$tenant,", "1-1-1-1-1").forEach { invalid ->
            assertThatThrownBy { RunPromotionConfiguration().runPromotionAdmission(query, invalid) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `initial start exposes actionable no-store promotion conflict`() {
        val service = mock(ResolutionRunService::class.java)
        val case = UUID.randomUUID()
        `when`(service.start(tenant, case, 4)).thenThrow(ResolutionRunPromotionRequiredException())
        val mvc =
            MockMvcBuilders
                .standaloneSetup(ResolutionRunController(service))
                .setControllerAdvice(RunPromotionExceptionHandler())
                .build()
        mvc
            .perform(post("/internal/v1/tenants/$tenant/cases/$case/resolution-runs").header("If-Match", "\"4\""))
            .andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-promotion-required"))
    }
}
