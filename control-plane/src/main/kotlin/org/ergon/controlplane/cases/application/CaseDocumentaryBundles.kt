package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.DocumentaryBundlePin
import java.time.Clock
import java.util.UUID

/** Case-owned complete bounded selection; only attachments at or before [throughVersion] may be returned. */
fun interface CaseDocumentaryPinReader {
    fun find(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): List<DocumentaryBundlePin>
}

class CaseDocumentaryBundleUnavailable : RuntimeException("exact documentary bundle is unavailable")

data class PinCaseDocumentaryBundleCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val expectedVersion: Long,
    val bundle: DocumentaryBundleRequirement,
)

/**
 * Exact historical lookup, optimistic append and projection use the existing case commit boundary.
 */
class CaseDocumentaryBundleService(
    private val events: CaseEventStore,
    private val bundles: EvidenceBundleRepository,
    private val committer: CaseEventCommitter,
    private val clock: Clock,
) {
    /**
     * Readiness evaluates suitability at the database recording instant; callers cannot select that instant.
     */
    fun pin(command: PinCaseDocumentaryBundleCommand): CaseWriteResult {
        require(command.expectedVersion > 0) { "If-Match version must be positive" }
        val tenant = TenantId(command.tenantId)
        val id = CaseId(command.caseId)
        val case = loadCase(tenant, id)
        if (case.streamVersion != command.expectedVersion) {
            throw ConcurrentCaseModificationException(command.expectedVersion, case.streamVersion)
        }
        val bundle = bundles.find(tenant, command.bundle.bundleId)
        if (bundle == null || bundle.sha256 != command.bundle.sha256) throw CaseDocumentaryBundleUnavailable()
        case.pinDocumentaryBundle(command.bundle, clock.instant())
        committer.commit(case, command.expectedVersion)
        return CaseWriteResult(id.value, case.status.name, case.streamVersion)
    }

    private fun loadCase(
        tenant: TenantId,
        id: CaseId,
    ): ErgonCase {
        val history = events.load(tenant, id)
        if (history.isEmpty()) throw CaseNotFoundException(tenant.value, id.value)
        return ErgonCase.rehydrate(id, tenant, history)
    }
}
