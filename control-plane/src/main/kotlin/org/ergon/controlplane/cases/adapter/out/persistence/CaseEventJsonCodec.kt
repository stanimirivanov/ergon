package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.ObservationRecorded
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class CaseEventJsonCodec(
    private val objectMapper: ObjectMapper,
) {
    fun encode(event: CaseEvent): EncodedCaseEvent =
        when (event) {
            is CaseOpened -> EncodedCaseEvent("CaseOpened", 1, objectMapper.writeValueAsString(event))
            is ObservationRecorded -> EncodedCaseEvent("ObservationRecorded", 1, objectMapper.writeValueAsString(event))
        }

    fun decode(
        eventType: String,
        schemaVersion: Int,
        payload: String,
    ): CaseEvent {
        require(schemaVersion == 1) { "unsupported $eventType schema version $schemaVersion" }
        return when (eventType) {
            "CaseOpened" -> objectMapper.readValue(payload, CaseOpened::class.java)
            "ObservationRecorded" -> objectMapper.readValue(payload, ObservationRecorded::class.java)
            else -> error("unsupported case event type $eventType")
        }
    }
}

data class EncodedCaseEvent(
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
)
