package org.ergon.controlplane.cases.application

import java.time.Instant
import java.util.UUID

data class OpenCaseCommand(
    val tenantId: UUID,
    val goal: String,
    val initialObservation: String,
)

data class RecordConnectorObservationCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val expectedVersion: Long,
    val connector: String,
    val reference: String,
    val content: String,
)

data class CaseWriteResult(
    val caseId: UUID,
    val status: String,
    val streamVersion: Long,
)

data class CaseTimeline(
    val caseId: UUID,
    val goal: String,
    val status: String,
    val streamVersion: Long,
    val entries: List<CaseTimelineEntry>,
)

data class CaseTimelineEntry(
    val streamVersion: Long,
    val eventType: String,
    val summary: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val observation: TimelineObservation,
)

data class TimelineObservation(
    val id: UUID,
    val originType: String,
    val provider: String,
    val reference: String?,
    val content: String,
)

class CaseNotFoundException(
    tenantId: UUID,
    caseId: UUID,
) : RuntimeException("Case $caseId was not found for tenant $tenantId")

class ConcurrentCaseModificationException(
    val expectedVersion: Long,
    val actualVersion: Long,
) : RuntimeException("Expected case version $expectedVersion but found $actualVersion")
