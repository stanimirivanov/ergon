package org.ergon.controlplane.resolution.adapter.out.evidence

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.application.CaseDocumentaryPinReader
import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.controlplane.resolution.application.PinnedDocumentaryEvidenceReader
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.DocumentaryBundleMaterial
import org.ergon.resolution.domain.DocumentaryClaimValidity
import org.ergon.resolution.domain.PinnedDocumentaryBundle
import org.springframework.stereotype.Component

/** Joins explicit application ports; later accepted candidates never replace a manifest's selected immutable claims. */
@Component
class HistoricalDocumentaryEvidenceReader(
    private val pins: CaseDocumentaryPinReader,
    private val bundles: EvidenceBundleRepository,
    private val claims: ClaimProposalRepository,
) : PinnedDocumentaryEvidenceReader {
    override fun find(
        tenant: TenantId,
        case: CaseId,
        throughVersion: Long,
    ): List<PinnedDocumentaryBundle> =
        pins.find(tenant, case, throughVersion).map { pin ->
            check(pin.streamVersion <= throughVersion) { "documentary reader exceeded prefix" }
            val bundle = checkNotNull(bundles.find(tenant, pin.reference.bundleId)) { "attached bundle is missing" }
            check(bundle.sha256 == pin.reference.sha256) { "attached bundle digest is inconsistent" }
            val validity =
                bundle.manifest.content.proposals
                    .filter { it.selected && it.target.kind == EvidenceProposalKind.CLAIM }
                    .map { reference ->
                        val stored =
                            checkNotNull(claims.find(tenant, reference.target.id)) { "selected claim is missing" }
                        check(stored.proposal.extraction == reference.extraction) {
                            "selected claim attribution is inconsistent"
                        }
                        DocumentaryClaimValidity(stored.proposal.claim.validFrom, stored.proposal.claim.validUntil)
                    }
            PinnedDocumentaryBundle(
                pin,
                DocumentaryBundleMaterial(
                    DocumentaryBundleRequirement(bundle.command.id, bundle.sha256),
                    bundle.manifest.assessedAt,
                    validity,
                ),
            )
        }
}
