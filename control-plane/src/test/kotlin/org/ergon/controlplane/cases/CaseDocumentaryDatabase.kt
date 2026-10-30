package org.ergon.controlplane.cases

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.adapter.out.persistence.CaseEventJsonCodec
import org.ergon.controlplane.cases.adapter.out.persistence.PostgresCaseDocumentaryPinReader
import org.ergon.controlplane.cases.adapter.out.persistence.PostgresCaseEventStore
import org.ergon.controlplane.cases.adapter.out.persistence.PostgresCaseProjectionWriter
import org.ergon.controlplane.cases.adapter.out.transaction.SpringTransactionRunner
import org.ergon.controlplane.cases.application.BindAccountAccessStateCommand
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseDocumentaryBundleService
import org.ergon.controlplane.cases.application.CaseEventCommitter
import org.ergon.controlplane.cases.application.IdentityGenerator
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.PinCaseDocumentaryBundleCommand
import org.ergon.controlplane.cases.application.PinResolutionContractCommand
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.contracts.DocumentaryContractFixtures
import org.ergon.controlplane.contracts.adapter.out.evidence.PublishedEvidenceBundleReferences
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionService
import org.ergon.controlplane.evidence.EvidenceBundleDatabase
import org.ergon.controlplane.resolution.adapter.out.evidence.HistoricalDocumentaryEvidenceReader
import org.ergon.controlplane.resolution.adapter.out.persistence.PostgresResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.controlplane.resolution.application.ResolutionRunIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.ergon.resolution.domain.CapabilityPolicyRule
import org.ergon.resolution.domain.ResolutionPolicy
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

internal class CaseDocumentaryDatabase(
    postgres: PostgreSQLContainer,
    broad: Boolean = true,
    target: String = "latest",
) {
    val b = EvidenceBundleDatabase(postgres, migrationTarget = target, broadValidity = broad)
    val tenant = b.f.source.tenant
    val bundle = b.repository.publish(b.db.admission, b.command, b.content).stored
    val reference = DocumentaryBundleRequirement(bundle.command.id, bundle.sha256)
    val transactions = SpringTransactionRunner(b.transactions)
    private val mapper = JsonMapper.builder().build()
    val events = PostgresCaseEventStore(b.db.jdbc, CaseEventJsonCodec(mapper))
    private val contracts = PostgresResolutionContractRevisionRepository(b.db.jdbc, ResolutionContractJsonCodec(mapper))
    private val ids = IdentityGenerator(UUID::randomUUID)
    private val commit = CaseEventCommitter(events, PostgresCaseProjectionWriter(b.db.jdbc), ids, transactions)

    // Deliberately historical business time: validity must use database attachment time instead.
    private val clock = Clock.fixed(b.f.now, ZoneOffset.UTC)
    val commands = CaseCommandService(events, contracts, ids, commit, clock)
    val attachments = CaseDocumentaryBundleService(events, b.repository, commit, clock)
    val pins = PostgresCaseDocumentaryPinReader(b.db.jdbc)
    val documentary = HistoricalDocumentaryEvidenceReader(pins, b.repository, b.db.claims)
    val readiness = ResolutionReadinessService(events, contracts, documentary)
    val contract =
        ResolutionContractRevisionService(
            DocumentaryContractFixtures.decoder,
            ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
            contracts,
            PublishedEvidenceBundleReferences(b.repository),
        ).publish(tenant, DocumentaryContractFixtures.document(reference.bundleId, reference.sha256)).contract
    private val step = contract.steps.single()
    val policy =
        ResolutionPolicy.define(
            ResolutionPolicyRevision.of("ergon.dev/policy/test/v1"),
            listOf(CapabilityPolicyRule(step.capability, step.risk, step.approval)),
        )
    val planner = ResolutionPlanningService(readiness, policy)
    val runs = PostgresResolutionRunRepository(b.db.jdbc)
    val runService =
        ResolutionRunService(
            planner,
            runs,
            ResolutionRunIdentityGenerator { ResolutionRunId(UUID.randomUUID()) },
            transactions,
        )

    fun openPreparedCase(): UUID {
        val case = commands.open(OpenCaseCommand(tenant.value, "Restore", "Locked")).caseId
        commands.pinResolutionContract(
            PinResolutionContractCommand(
                tenant.value,
                case,
                1,
                contract.key.value,
                contract.revision.value,
            ),
        )
        commands.recordConnectorObservation(
            RecordConnectorObservationCommand(
                tenant.value,
                case,
                2,
                "test",
                "account",
                "Locked",
            ),
        )
        val observation = events.load(tenant, CaseId(case)).last() as ObservationRecorded
        commands.bindAccountAccessState(
            BindAccountAccessStateCommand(
                tenant.value,
                case,
                3,
                observation.observationId,
                org.ergon.cases.domain.AccountAccessState.LOCKED,
            ),
        )
        return case
    }

    fun attach(
        case: UUID,
        version: Long = 4,
        selected: DocumentaryBundleRequirement = reference,
    ) = attachments.pin(PinCaseDocumentaryBundleCommand(tenant.value, case, version, selected))
}
