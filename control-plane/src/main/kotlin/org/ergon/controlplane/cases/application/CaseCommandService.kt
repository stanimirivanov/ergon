package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseGoal
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.cases.domain.ObservationOrigin
import org.ergon.cases.domain.SourceObservation
import org.ergon.cases.domain.TenantId
import java.time.Clock

/**
 * Handles case write commands.
 *
 * Every command follows the same shape: load or create the aggregate,
 * mutate it, then persist its events and projection in one transaction via
 * [transactionRunner]. Connector commands check optimistic concurrency twice:
 * first against the loaded aggregate before beginning write work, and again
 * authoritatively inside [CaseEventStore.append] against a fresh read taken
 * under lock. Only the second check is race-safe; the first avoids entering a
 * write transaction for an already stale command.
 */
class CaseCommandService(
    private val eventStore: CaseEventStore,
    private val projectionWriter: CaseProjectionWriter,
    private val identities: IdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Opens a new case from a requester's initial observation.
     *
     * A successful write returns at stream version one; there is no
     * expected-version precondition because the case identity is newly
     * generated.
     *
     * @return the committed case identity, open status, and stream version one.
     * @throws IllegalArgumentException if the goal or initial observation
     *   violates its domain constraints.
     */
    fun open(command: OpenCaseCommand): CaseWriteResult {
        val tenantId = TenantId(command.tenantId)
        val caseId = CaseId(identities.next())
        val case =
            ErgonCase.open(
                id = caseId,
                tenantId = tenantId,
                goal = CaseGoal.of(command.goal),
                initialObservation =
                    SourceObservation.create(
                        id = ObservationId(identities.next()),
                        origin = ObservationOrigin.requesterApi(),
                        content = command.initialObservation,
                        observedAt = clock.instant(),
                    ),
            )

        persist(case, expectedVersion = 0)
        return case.toWriteResult()
    }

    /**
     * Records connector evidence against the caller's expected version.
     *
     * @return the committed case identity, status, and incremented stream
     *   version.
     * @throws CaseNotFoundException when the tenant-scoped case is absent.
     * @throws ConcurrentCaseModificationException when another command has
     *  advanced the stream.
     * @throws IllegalArgumentException when observation or precondition values
     *  violate domain invariants.
     */
    fun recordConnectorObservation(command: RecordConnectorObservationCommand): CaseWriteResult {
        require(command.expectedVersion > 0) { "If-Match version must be positive" }
        val tenantId = TenantId(command.tenantId)
        val caseId = CaseId(command.caseId)
        val history = eventStore.load(tenantId, caseId)
        if (history.isEmpty()) {
            throw CaseNotFoundException(command.tenantId, command.caseId)
        }
        val case = ErgonCase.rehydrate(caseId, tenantId, history)
        if (case.streamVersion != command.expectedVersion) {
            throw ConcurrentCaseModificationException(command.expectedVersion, case.streamVersion)
        }

        case.record(
            SourceObservation.create(
                id = ObservationId(identities.next()),
                origin = ObservationOrigin.connector(command.connector, command.reference),
                content = command.content,
                observedAt = clock.instant(),
            ),
        )
        persist(case, command.expectedVersion)
        return case.toWriteResult()
    }

    /**
     * Binds an account-access state to evidence already recorded in the case.
     *
     * @return the committed case identity, status, and incremented stream
     *   version.
     * @throws CaseNotFoundException when the tenant-scoped case is absent.
     * @throws ConcurrentCaseModificationException when another command has
     *   advanced the stream.
     * @throws IllegalArgumentException when the observation is absent, is not
     *   connector-authored, was already bound, or the precondition is invalid.
     */
    fun bindAccountAccessState(command: BindAccountAccessStateCommand): CaseWriteResult {
        require(command.expectedVersion > 0) { "If-Match version must be positive" }
        val tenantId = TenantId(command.tenantId)
        val caseId = CaseId(command.caseId)
        val history = eventStore.load(tenantId, caseId)
        if (history.isEmpty()) {
            throw CaseNotFoundException(command.tenantId, command.caseId)
        }
        val case = ErgonCase.rehydrate(caseId, tenantId, history)
        if (case.streamVersion != command.expectedVersion) {
            throw ConcurrentCaseModificationException(command.expectedVersion, case.streamVersion)
        }

        case.bindAccountAccessState(
            factId = FactId(identities.next()),
            observationId = ObservationId(command.observationId),
            state = command.state,
            boundAt = clock.instant(),
        )
        persist(case, command.expectedVersion)
        return case.toWriteResult()
    }

    private fun persist(
        case: ErgonCase,
        expectedVersion: Long,
    ) {
        val events = case.pendingEvents().map { NewCaseEvent(identities.next(), it) }
        transactionRunner.required {
            val stored = eventStore.append(case.tenantId, case.id, expectedVersion, events)
            projectionWriter.project(case, stored)
        }
        case.markChangesCommitted()
    }

    private fun ErgonCase.toWriteResult() =
        CaseWriteResult(
            caseId = id.value,
            status = status.name,
            streamVersion = streamVersion,
        )
}
