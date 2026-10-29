package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.ClaimProposalService
import org.ergon.controlplane.evidence.application.EvidenceReviewProposalReader
import org.ergon.controlplane.evidence.application.EvidenceReviewRepository
import org.ergon.controlplane.evidence.application.EvidenceReviewService
import org.ergon.controlplane.evidence.application.ProcedureProposalRepository
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.evidence.application.VerifiedReviewProposalReader
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class EvidenceReviewConfiguration {
    @Bean
    fun evidenceReviewProposalReader(
        claims: ClaimProposalRepository,
        procedures: ProcedureProposalRepository,
        claimReads: ClaimProposalService,
        procedureReads: ProcedureProposalService,
    ): EvidenceReviewProposalReader = VerifiedReviewProposalReader(claims, procedures, claimReads, procedureReads)

    @Bean
    fun evidenceReviewService(
        authorities: HumanAuthorityRepository,
        proposals: EvidenceReviewProposalReader,
        repository: EvidenceReviewRepository,
        clock: Clock,
    ) = EvidenceReviewService(authorities, proposals, repository, clock)
}
