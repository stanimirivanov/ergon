package org.ergon.controlplane.followup

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.OwnedInformationRequestsReader
import org.ergon.controlplane.followup.application.OwnedInformationRequestsService
import org.ergon.controlplane.followup.application.RequesterAnswerWriteGuard
import org.ergon.controlplane.followup.application.RequesterCaseQuestionsReader
import org.ergon.controlplane.followup.application.RequesterCaseQuestionsService
import org.ergon.controlplane.followup.application.RequesterCaseWriteGuard
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.RequesterInformationRequestService
import org.ergon.controlplane.followup.application.RequesterInformationResponseRepository
import org.ergon.controlplane.followup.application.RequesterInformationResponseService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Composes requester questions, answers and protected reads without infrastructure in application policy. */
@Configuration(proxyBeanMethods = false)
class RequesterInformationRequestConfiguration {
    @Bean
    fun requesterAnswers(
        cases: RequesterAnswerWriteGuard,
        responses: RequesterInformationResponseRepository,
        transactions: TransactionRunner,
        clock: Clock,
    ): RequesterInformationResponseService = RequesterInformationResponseService(cases, responses, transactions, clock)

    @Bean
    fun ownedInformationRequests(
        claims: HumanFollowUpClaimRepository,
        cases: RequesterCaseWriteGuard,
        reader: OwnedInformationRequestsReader,
        transactions: TransactionRunner,
        clock: Clock,
    ): OwnedInformationRequestsService = OwnedInformationRequestsService(claims, cases, reader, transactions, clock)

    @Bean
    fun requesterCaseQuestions(reader: RequesterCaseQuestionsReader): RequesterCaseQuestionsService =
        RequesterCaseQuestionsService(
            reader,
        )

    @Bean
    fun requesterInformationRequests(
        claims: HumanFollowUpClaimRepository,
        cases: RequesterCaseWriteGuard,
        requests: RequesterInformationRequestRepository,
        transactions: TransactionRunner,
        clock: Clock,
    ): RequesterInformationRequestService =
        RequesterInformationRequestService(
            claims,
            cases,
            requests,
            transactions,
            clock,
        )
}
