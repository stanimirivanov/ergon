package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.RecordedQuestionResponse
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Both history projections use these aliases; the LEFT JOIN's response identity is the absence sentinel. */
internal fun ResultSet.recordedQuestionResponse(): RecordedQuestionResponse? =
    getObject("response_id", UUID::class.java)?.let { id ->
        RecordedQuestionResponse(
            id,
            getString("answer"),
            getObject("responded_at", OffsetDateTime::class.java).toInstant(),
            getObject("response_recorded_at", OffsetDateTime::class.java).toInstant(),
        )
    }
