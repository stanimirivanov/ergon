package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ApprovalDecisionIdentityGenerator
import org.ergon.controlplane.resolution.application.ApprovalDecisionRecords
import org.ergon.controlplane.resolution.application.ApprovalDecisionRepository
import org.ergon.controlplane.resolution.application.ApprovalRequestRepository
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.PinnedRunEvidenceReader
import org.ergon.controlplane.resolution.application.RecordedRunTransitionReader
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAssessmentService
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofCompletion
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunApprovalCommandRepository
import org.ergon.controlplane.resolution.application.RunApprovalDecisionService
import org.ergon.controlplane.resolution.application.RunApprovalReviewReader
import org.ergon.controlplane.resolution.application.RunApprovalReviewService
import org.ergon.controlplane.resolution.application.RunOutcomeVerificationService
import org.ergon.controlplane.resolution.application.RunProofCommandRepository
import org.ergon.controlplane.resolution.application.RunProofFinalizationService
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentIdentityGenerator
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.ergon.controlplane.resolution.application.RunSupervisorCandidateReader
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires immutable initial assignments, revisioned transfers and current-supervisor browser reads. */
@Configuration(proxyBeanMethods = false)
class RunSupervisorConfiguration {
    @Bean
    @Suppress("LongParameterList") // Keep authority, evidence, transaction and audit dependencies explicit.
    fun runProofFinalizationService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        assessments: ResolutionOutcomeProofAssessmentService,
        completion: ResolutionOutcomeProofCompletion,
        commands: RunProofCommandRepository,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.proof-finalization.enabled:false}") enabled: Boolean,
    ) = RunProofFinalizationService(
        assignments,
        authorities,
        runs,
        transitions,
        assessments,
        completion,
        commands,
        transactions,
        clock,
        enabled,
    )

    @Bean
    fun runOutcomeVerificationService(
        contracts: ResolutionContractRevisionRepository,
        cases: CaseEventStore,
        transitions: ResolutionRunTransitionRepository,
    ) = RunOutcomeVerificationService(contracts, cases, transitions)

    @Bean
    fun runApprovalReviewService(
        reader: RunApprovalReviewReader,
        authorities: HumanAuthorityRepository,
        @Value("\${ergon.run-supervision.approval.enabled:false}") enabled: Boolean,
    ) = RunApprovalReviewService(reader, authorities, enabled)

    @Bean
    @Suppress("LongParameterList") // Distinct consumed ports keep authority, supervision and audit ownership explicit.
    fun runApprovalDecisionService(
        requests: ApprovalRequestRepository,
        runs: ResolutionRunRepository,
        authorities: HumanAuthorityRepository,
        decisions: ApprovalDecisionRepository,
        assignments: RunSupervisorAssignmentRepository,
        states: ResolutionRunTransitionRepository,
        commands: RunApprovalCommandRepository,
        reader: RunApprovalReviewReader,
        identities: ApprovalDecisionIdentityGenerator,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.approval.enabled:false}") enabled: Boolean,
    ) = RunApprovalDecisionService(
        ApprovalDecisionRecords(requests, runs, authorities, decisions),
        assignments,
        states,
        commands,
        reader,
        identities,
        transactions,
        clock,
        enabled,
    )

    @Bean
    @Suppress("LongParameterList") // Application-owned identity, state, and audit boundaries remain distinct.
    fun runSupervisorHandoverService(
        assignments: RunSupervisorAssignmentRepository,
        handovers: RunSupervisorHandoverRepository,
        candidates: RunSupervisorCandidateReader,
        authorities: HumanAuthorityRepository,
        transitions: ResolutionRunTransitionRepository,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.handover.enabled:false}") handoverEnabled: Boolean,
    ) = RunSupervisorHandoverService(
        assignments,
        handovers,
        candidates,
        authorities,
        transitions,
        transactions,
        clock,
        handoverEnabled,
    )

    @Bean
    fun runSupervisorAssignmentIdentityGenerator() = RunSupervisorAssignmentIdentityGenerator { UUID.randomUUID() }

    @Bean
    fun runSupervisorAssignmentService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        identities: RunSupervisorAssignmentIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = RunSupervisorAssignmentService(assignments, authorities, identities, transactionRunner, clock)

    @Bean
    @Suppress("LongParameterList") // Composition root wires the assigned read's distinct ports explicitly.
    fun assignedRunConsoleService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        transactionRunner: TransactionRunner,
        clock: Clock,
        receipts: CapabilityInvocationReceiptRepository,
        historyReader: RecordedRunTransitionReader,
        approvalReviews: RunApprovalReviewService,
        pinnedEvidence: PinnedRunEvidenceReader,
        outcomeVerification: RunOutcomeVerificationService,
        @Value("\${ergon.run-supervision.handover.enabled:false}") handoverEnabled: Boolean,
        @Value("\${ergon.run-supervision.proof-finalization.enabled:false}") proofFinalizationEnabled: Boolean,
    ) = AssignedRunConsoleService(
        assignments,
        authorities,
        runs,
        transitions,
        transactionRunner,
        clock,
        receipts,
        historyReader,
        approvalReviews,
        pinnedEvidence,
        outcomeVerification,
        handoverEnabled,
        proofFinalizationEnabled,
    )
}
