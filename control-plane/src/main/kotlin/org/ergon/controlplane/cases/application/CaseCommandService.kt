package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.AccountAccessState
import org.ergon.cases.domain.CaseGoal
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.cases.domain.ObservationOrigin
import org.ergon.cases.domain.SourceObservation
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.contracts.application.ContractRevisionNotFoundException
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.identity.domain.TenantId
import java.time.Clock

/**
 * Handles case write commands.
 *
 * Every command follows the same shape: load or create the aggregate,
 * mutate it, then persist its events and projection in one transaction via
 * [eventCommitter]. Existing-case commands check optimistic concurrency twice:
 * first against the loaded aggregate before beginning write work, and again
 * authoritatively inside [CaseEventStore.append] against a fresh read taken
 * under lock. Only the second check is race-safe; the first avoids entering a
 * write transaction for an already stale command.
 */
class CaseCommandService(
    private val eventStore: CaseEventStore,
    private val contractRevisions: ResolutionContractRevisionRepository,
    private val identities: IdentityGenerator,
    private val eventCommitter: CaseEventCommitter,
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

        eventCommitter.commit(case, expectedVersion = 0)
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
        val case = loadCase(tenantId, caseId)
        case.requireExpectedVersion(command.expectedVersion)

        case.record(
            SourceObservation.create(
                id = ObservationId(identities.next()),
                origin = ObservationOrigin.connector(command.connector, command.reference),
                content = command.content,
                observedAt = clock.instant(),
            ),
        )
        eventCommitter.commit(case, command.expectedVersion)
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
        val case = loadCase(tenantId, caseId)
        case.requireExpectedVersion(command.expectedVersion)

        case.bindAccountAccessState(
            factId = FactId(identities.next()),
            observationId = ObservationId(command.observationId),
            state = command.state,
            boundAt = clock.instant(),
        )
        eventCommitter.commit(case, command.expectedVersion)
        return case.toWriteResult()
    }

    /**
     * Pins an existing tenant contract revision to a case.
     *
     * The published revision is looked up through the contract application
     * port; the case never reads another capability's table. Published
     * revisions cannot be deleted, so verifying the reference before the case
     * write transaction cannot become stale.
     *
     * @return the committed case identity, status, and incremented stream version.
     * @throws CaseNotFoundException when the tenant-scoped case is absent.
     * @throws ContractRevisionNotFoundException when the exact revision is not
     *   published for the same tenant.
     * @throws ConcurrentCaseModificationException when another command has advanced the stream.
     * @throws IllegalArgumentException when a contract is already pinned or a
     *   precondition or contract identity is invalid.
     */
    fun pinResolutionContract(command: PinResolutionContractCommand): CaseWriteResult {
        require(command.expectedVersion > 0) { "If-Match version must be positive" }
        val tenantId = TenantId(command.tenantId)
        val caseId = CaseId(command.caseId)
        val case = loadCase(tenantId, caseId)
        case.requireExpectedVersion(command.expectedVersion)
        val key = ResolutionContractKey.of(command.contractKey)
        val revision = ResolutionContractRevision.of(command.contractRevision)
        val stored =
            contractRevisions.find(tenantId, key, revision)
                ?: throw ContractRevisionNotFoundException(key, revision)

        case.pinResolutionContract(
            ResolutionContractIdentity(stored.contract.key, stored.contract.revision),
            clock.instant(),
        )
        eventCommitter.commit(case, command.expectedVersion)
        return case.toWriteResult()
    }

    private fun loadCase(
        tenantId: TenantId,
        caseId: CaseId,
    ): ErgonCase {
        val history = eventStore.load(tenantId, caseId)
        if (history.isEmpty()) {
            throw CaseNotFoundException(tenantId.value, caseId.value)
        }
        return ErgonCase.rehydrate(caseId, tenantId, history)
    }

    private fun ErgonCase.requireExpectedVersion(expectedVersion: Long) {
        if (streamVersion != expectedVersion) {
            throw ConcurrentCaseModificationException(expectedVersion, streamVersion)
        }
    }

    private fun ErgonCase.toWriteResult() =
        CaseWriteResult(
            caseId = id.value,
            status = status.name,
            streamVersion = streamVersion,
        )
}

/** Commits pending case events and their synchronous projections atomically. */
class CaseEventCommitter(
    private val eventStore: CaseEventStore,
    private val projectionWriter: CaseProjectionWriter,
    private val identities: IdentityGenerator,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Persists all pending events after [expectedVersion], then clears them
     * only after the required transaction has committed successfully.
     *
     * @throws ConcurrentCaseModificationException when durable stream state
     *   no longer equals [expectedVersion].
     * @throws IllegalArgumentException when [expectedVersion] is negative or
     *   [case] has no pending events.
     */
    fun commit(
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
}
