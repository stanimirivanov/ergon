package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.AccountAccessState
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

/**
 * Attributes a typed account state to an existing observation while the case
 * remains at [expectedVersion].
 */
data class BindAccountAccessStateCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val expectedVersion: Long,
    val observationId: UUID,
    val state: AccountAccessState,
)

/**
 * Pins one published resolution contract revision while the case remains at
 * [expectedVersion]. [contractKey] must be canonical and [contractRevision]
 * must be positive; the application validates both through domain value types.
 */
data class PinResolutionContractCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val expectedVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
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
    val resolutionContract: PinnedResolutionContract?,
    val entries: List<CaseTimelineEntry>,
)

/**
 * Exact contract revision selected for a case, with event and persistence time.
 *
 * [pinnedAt] is the application decision time; [recordedAt] is when PostgreSQL
 * made the pin durable. The stream version identifies its position in case
 * history even though the pin is deliberately not a source timeline entry.
 */
data class PinnedResolutionContract(
    val key: String,
    val revision: Int,
    val streamVersion: Long,
    val pinnedAt: Instant,
    val recordedAt: Instant,
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

/** Tenant-scoped account-access facts and the case version from which they were projected. */
data class CaseAccountAccessFacts(
    val caseId: UUID,
    val streamVersion: Long,
    val facts: List<AccountAccessFact>,
)

/**
 * A typed account state with the evidence and timestamps needed to audit it.
 *
 * [accountReference] is inherited from [observationId]. [boundAt] records when
 * the application accepted the meaning; [recordedAt] records when PostgreSQL
 * made its event durable.
 */
data class AccountAccessFact(
    val factId: UUID,
    val streamVersion: Long,
    val observationId: UUID,
    val accountReference: String,
    val state: AccountAccessState,
    val boundAt: Instant,
    val recordedAt: Instant,
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
