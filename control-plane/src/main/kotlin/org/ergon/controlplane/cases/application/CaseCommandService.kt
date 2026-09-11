package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseGoal
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.ObservationId
import org.ergon.cases.domain.ObservationOrigin
import org.ergon.cases.domain.SourceObservation
import org.ergon.cases.domain.TenantId
import java.time.Clock

class CaseCommandService(
    private val eventStore: CaseEventStore,
    private val projectionWriter: CaseProjectionWriter,
    private val identities: IdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
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
