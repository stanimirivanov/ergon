package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.CaseVerifiedResolved
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.cases.domain.ResolutionContractRevisionPinned
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Versioned JSON codec at the boundary between domain events and their durable payloads. */
@Component
class CaseEventJsonCodec(
    private val objectMapper: ObjectMapper,
) {
    fun encode(event: CaseEvent): EncodedCaseEvent =
        when (event) {
            is AccountAccessStateBound -> {
                EncodedCaseEvent("AccountAccessStateBound", 1, objectMapper.writeValueAsString(event))
            }

            is CaseOpened -> {
                EncodedCaseEvent("CaseOpened", 1, objectMapper.writeValueAsString(event))
            }

            is CaseVerifiedResolved -> {
                EncodedCaseEvent("CaseVerifiedResolved", 1, objectMapper.writeValueAsString(event))
            }

            is ObservationRecorded -> {
                EncodedCaseEvent("ObservationRecorded", 1, objectMapper.writeValueAsString(event))
            }

            is ResolutionContractRevisionPinned -> {
                EncodedCaseEvent("ResolutionContractRevisionPinned", 1, objectMapper.writeValueAsString(event))
            }
        }

    fun decode(
        eventType: String,
        schemaVersion: Int,
        payload: String,
    ): CaseEvent {
        require(schemaVersion == 1) { "unsupported $eventType schema version $schemaVersion" }
        return when (eventType) {
            "AccountAccessStateBound" -> {
                objectMapper.readValue(payload, AccountAccessStateBound::class.java)
            }

            "CaseOpened" -> {
                objectMapper.readValue(payload, CaseOpened::class.java)
            }

            "CaseVerifiedResolved" -> {
                objectMapper.readValue(payload, CaseVerifiedResolved::class.java)
            }

            "ObservationRecorded" -> {
                objectMapper.readValue(payload, ObservationRecorded::class.java)
            }

            "ResolutionContractRevisionPinned" -> {
                objectMapper.readValue(payload, ResolutionContractRevisionPinned::class.java)
            }

            else -> {
                error("unsupported case event type $eventType")
            }
        }
    }
}

/** Storage representation selected by event type and schema version before persistence. */
data class EncodedCaseEvent(
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
)
