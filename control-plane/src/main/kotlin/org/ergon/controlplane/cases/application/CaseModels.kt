package org.ergon.controlplane.cases.application

import java.time.Instant
import java.util.UUID

/** Intent to create a tenant-scoped case from one requester observation. */
data class OpenCaseCommand(
    val tenantId: UUID,
    val goal: String,
    val initialObservation: String,
)

/** Intent to append connector evidence when the case still has [expectedVersion]. */
data class RecordConnectorObservationCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val expectedVersion: Long,
    val connector: String,
    val reference: String,
    val content: String,
)

/** Identity and concurrency metadata returned after a committed case command. */
data class CaseWriteResult(
    val caseId: UUID,
    val status: String,
    val streamVersion: Long,
)

/** Application query model for a case and its complete ordered timeline. */
data class CaseTimeline(
    val caseId: UUID,
    val goal: String,
    val status: String,
    val streamVersion: Long,
    val entries: List<CaseTimelineEntry>,
)

/** One case event rendered for chronological inspection. */
data class CaseTimelineEntry(
    val streamVersion: Long,
    val eventType: String,
    val summary: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val observation: TimelineObservation,
)

/** Source observation embedded in a timeline entry without semantic interpretation. */
data class TimelineObservation(
    val id: UUID,
    val originType: String,
    val provider: String,
    val reference: String?,
    val content: String,
)

/** Signals tenant-scoped absence without revealing whether another tenant owns the identity. */
class CaseNotFoundException(
    tenantId: UUID,
    caseId: UUID,
) : RuntimeException("Case $caseId was not found for tenant $tenantId")

/** Signals that a caller based its command on an obsolete case stream version. */
class ConcurrentCaseModificationException(
    val expectedVersion: Long,
    val actualVersion: Long,
) : RuntimeException("Expected case version $expectedVersion but found $actualVersion")
