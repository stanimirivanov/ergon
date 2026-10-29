package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.evidence.application.EvidenceBundlePreparer
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.controlplane.evidence.application.EvidenceBundleService
import org.ergon.controlplane.evidence.application.EvidenceReviewProposalReader
import org.ergon.controlplane.evidence.application.EvidenceReviewRepository
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class EvidenceBundleConfiguration {
    @Bean
    fun evidenceBundlePreparer(
        contradictions: ContradictionService,
        proposals: EvidenceReviewProposalReader,
        reviews: EvidenceReviewRepository,
    ) = EvidenceBundlePreparer(contradictions, proposals, reviews)

    @Bean
    fun evidenceBundleService(
        authorities: HumanAuthorityRepository,
        preparer: EvidenceBundlePreparer,
        repository: EvidenceBundleRepository,
        clock: Clock,
    ) = EvidenceBundleService(authorities, preparer, repository, clock)
}
