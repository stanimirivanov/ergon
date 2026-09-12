package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

/**
 * Loads and appends to a case's durable event stream.
 *
 * Stream versions are optimistic-concurrency tokens. [append] must compare the
 * caller's expected version with durable state and reject stale writes before
 * inserting any event. Callers must run an append and its projection update in
 * one [TransactionRunner.required] block so either both become visible or both
 * roll back.
 */
interface CaseEventStore {
    /**
     * @return the complete history in ascending stream-version order, or an
     *   empty list when the tenant-scoped case does not exist.
     */
    fun load(
        tenantId: TenantId,
        caseId: CaseId,
    ): List<CaseEvent>

    /**
     * Appends non-empty [events] after verifying [expectedVersion].
     *
     * This operation does not establish the application transaction itself;
     * the caller must invoke it inside [TransactionRunner.required].
     *
     * @param expectedVersion the non-negative stream version observed by the
     *   caller; use zero only when creating a stream.
     * @return stored events in input order, with contiguous versions beginning
     *   at `[expectedVersion] + 1` and their database recording times.
     * @throws ConcurrentCaseModificationException if the stream's actual
     *   version doesn't equal [expectedVersion] at append time — the caller
     *   loaded stale history and must reload before retrying.
     * @throws IllegalArgumentException if [expectedVersion] is negative or
     *   [events] is empty.
     */
    fun append(
        tenantId: TenantId,
        caseId: CaseId,
        expectedVersion: Long,
        events: List<NewCaseEvent>,
    ): List<StoredCaseEvent>
}

/** Event paired with an application-generated identity that must be globally unique. */
data class NewCaseEvent(
    val eventId: UUID,
    val event: CaseEvent,
)

/** Persisted event metadata returned by [CaseEventStore.append]. */
data class StoredCaseEvent(
    val eventId: UUID,
    val streamVersion: Long,
    val event: CaseEvent,
    val recordedAt: Instant,
)

/**
 * Projects newly appended events onto the read-optimized tables behind
 * [CaseTimelineRepository].
 *
 * Synchronous consistency depends on callers invoking [project] immediately
 * after [CaseEventStore.append] in the same [TransactionRunner.required]
 * block. A projection failure must therefore roll back the event append too.
 */
interface CaseProjectionWriter {
    /**
     * Applies exactly the [events] just appended for [case], in stream order.
     *
     * @throws IllegalArgumentException if [events] is empty.
     */
    fun project(
        case: ErgonCase,
        events: List<StoredCaseEvent>,
    )
}

/** Read-side lookup for the timeline projected from a [CaseEventStore]. */
interface CaseTimelineRepository {
    /**
     * @return the current timeline, or `null` if no case exists for this
     *   [tenantId]/[caseId] pair. `null` means "does not exist," never
     *   "failed to load" — infrastructure failures still surface as
     *   exceptions from this call.
     */
    fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseTimeline?
}

/** Read-side lookup for typed account-access facts projected from case events. */
interface CaseAccountAccessFactRepository {
    /**
     * @return the current case version and facts in stream order, or `null`
     *   when the tenant-scoped case does not exist.
     */
    fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseAccountAccessFacts?
}

/** Supplies unpredictable identities without coupling use cases to a UUID implementation. */
fun interface IdentityGenerator {
    /** @return a fresh identity suitable for durable use. */
    fun next(): UUID
}

/** Defines application-owned transaction boundaries without exposing a framework API. */
interface TransactionRunner {
    /**
     * Runs [block] in a required transaction: join the current transaction if
     * one exists, otherwise start one. A newly started transaction commits
     * before this method returns. An exception is propagated and prevents that
     * transaction from committing; an outer transaction still owns its final
     * outcome when this method joins it.
     *
     * @param block the unit of work, which must return a non-null result.
     * @return the value produced by [block].
     */
    fun <T : Any> required(block: () -> T): T
}
