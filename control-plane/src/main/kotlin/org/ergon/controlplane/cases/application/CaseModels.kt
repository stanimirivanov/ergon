package org.ergon.controlplane.cases.application

import java.time.Instant
import java.util.UUID

data class OpenCaseCommand(
    val tenantId: UUID,
    val goal: String,
    val initialObservation: String,
)

/**
 * Appends connector evidence only if [expectedVersion] is the current positive
 * stream version.
 */
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

/** Read-model entry preserving both source occurrence time and database recording time. */
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

/**
 * Signals tenant-scoped absence without revealing whether the identity exists
 * under a different tenant.
 */
class CaseNotFoundException(
    tenantId: UUID,
    caseId: UUID,
) : RuntimeException("Case $caseId was not found for tenant $tenantId")

/**
 * Signals that a command's optimistic-concurrency precondition is stale.
 * [expectedVersion] is the caller's token and [actualVersion] is the durable
 * stream version observed while checking it, allowing the caller to reload
 * before deciding whether to retry.
 */
class ConcurrentCaseModificationException(
    val expectedVersion: Long,
    val actualVersion: Long,
) : RuntimeException("Expected case version $expectedVersion but found $actualVersion")
