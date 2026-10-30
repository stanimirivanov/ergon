package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.CaseId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.PinnedDocumentaryBundle

/** Complete case-prefix attachments plus immutable selected validity, after the caller establishes case/run access. */
fun interface PinnedDocumentaryEvidenceReader {
    /** Storage/integrity failures propagate, never become missing evidence. No latest qualification or remote I/O. */
    fun find(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): List<PinnedDocumentaryBundle>
}
