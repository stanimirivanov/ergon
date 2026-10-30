package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.application.BindAccountAccessStateCommand
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseDocumentaryBundleService
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.PinCaseDocumentaryBundleCommand
import org.ergon.controlplane.cases.application.PinResolutionContractCommand
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.contracts.DocumentaryContractFixtures
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionService
import org.ergon.controlplane.evidence.application.ClaimProposalCommand
import org.ergon.controlplane.evidence.application.ClaimProposalService
import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.evidence.application.EvidenceBundleService
import org.ergon.controlplane.evidence.application.EvidenceReviewService
import org.ergon.controlplane.evidence.application.InvalidClaim
import org.ergon.controlplane.evidence.application.InvalidEvidenceBundle
import org.ergon.controlplane.evidence.application.ProcedureCommand
import org.ergon.controlplane.evidence.application.ProcedureOperationBinding
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.controlplane.resolution.application.ResolutionRunNotReadyException
import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

/** Real Spring use cases, parsers and PostgreSQL; no mocked ports, browser or external server calls. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SourceToContractIntegrationTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val jdbc: JdbcClient,
) {
    private val tenant = TenantId(UUID.randomUUID())
    private val actor = HumanActorId(UUID.randomUUID())

    private inline fun <reified T : Any> bean(): T = context.getBean(T::class.java)

    @Test
    fun `licensed sources become reviewed adjudicated bundled and case pinned without granting operational truth`() {
        seedReviewer()
        val sources = listOf("petstore-readme.md", "review-runbook.md", "petstore.json").map(::register)
        val claims = proposeClaims(sources[0])
        val procedure = proposeProcedure(sources[1], sources[2])
        val command = reviewedBundle(claims, procedure)
        val bundles = bean<EvidenceBundleService>()
        val published = bundles.publish(tenant, actor, command).stored
        assertThat(bundles.publish(tenant, actor, command).created).isFalse()
        assertThat(published.manifest.content.sources).containsExactlyInAnyOrderElementsOf(sources.map { it.revision })
        assertThat(
            published.manifest.content.proposals
                .count { it.selected },
        ).isEqualTo(2)
        assertThat(published.manifest.content.proposals).hasSize(3)
        consumeBundle(DocumentaryBundleRequirement(command.id, published.sha256))
        assertThat(bundles.get(tenant, actor, command.id)).isEqualTo(published)
        assertThatThrownBy { bundles.get(tenant, HumanActorId(UUID.randomUUID()), command.id) }
            .isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { bundles.get(TenantId(UUID.randomUUID()), actor, command.id) }
            .isInstanceOf(SourceUnavailable::class.java)
    }

    private fun register(name: String): EvidenceSource {
        val source = CompilerAcceptanceFixtures.source(tenant, name)
        val result =
            bean<SourceRevisionService>().register(
                source.revision,
                actor,
                source.format,
                source.text.toByteArray(),
            )
        assertThat(result.created).isTrue()
        return source
    }

    private fun proposeClaims(source: EvidenceSource): List<UUID> {
        val compiled =
            context
                .getBean("sourceCompilationService", SourceCompilationService::class.java)
                .compile(source.revision, actor)
        val index = compiled.observations.indexOfFirst { it.citation.quote.contains("stand-alone server") }
        assertThat(index).isNotNegative()
        val service = bean<ClaimProposalService>()
        return listOf(true, false).map { supported ->
            // Deliberate opposed human bindings in a fictional scope, not contradictory upstream assertions.
            val claim =
                DocumentaryClaim(
                    DocumentaryProperty.SUPPORT,
                    DocumentaryValue.Support(supported),
                    "petstore-training",
                    "compiler-acceptance",
                    Instant.parse("0001-01-01T00:00:00Z"),
                    Instant.parse("9999-12-31T23:59:59.999999Z"),
                )
            val command = ClaimProposalCommand(UUID.randomUUID(), compiled.extraction.id, index, claim)
            assertThatThrownBy {
                service.propose(source.revision, actor, command.copy(extractionId = UUID.randomUUID()))
            }.isInstanceOf(InvalidClaim::class.java)
            val proposal = service.propose(source.revision, actor, command).stored.proposal
            assertThat(proposal.citation.verifyAgainst(source)).isTrue()
            accept(EvidenceReviewTarget(EvidenceProposalKind.CLAIM, proposal.id), proposal.extraction.id)
            proposal.id
        }
    }

    private fun proposeProcedure(
        markdown: EvidenceSource,
        api: EvidenceSource,
    ): UUID {
        val md =
            context
                .getBean("sourceCompilationService", SourceCompilationService::class.java)
                .compile(markdown.revision, actor)
        val operations =
            context
                .getBean("openApiCompilationService", SourceCompilationService::class.java)
                .compile(api.revision, actor)
        val command =
            ProcedureCommand(
                UUID.randomUUID(),
                md.extraction.id,
                md.observations.indexOfFirst { it.kind == SourceObservationKind.ORDERED_LIST },
                "Inspect the documented Petstore operation",
                listOf(
                    ProcedureOperationBinding(
                        1,
                        api.revision,
                        operations.extraction.id,
                        operations.observations.indexOfFirst { it.jsonPointer == "/paths/~1pets/get" },
                    ),
                ),
            )
        val proposal = bean<ProcedureProposalService>().propose(markdown.revision, actor, command).stored.proposal
        assertThat(proposal.content.steps).hasSize(2)
        assertThat(
            proposal.content.steps
                .first()
                .operation!!
                .citation
                .verifyAgainst(api),
        ).isTrue()
        accept(EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, proposal.id), proposal.extraction.id)
        return proposal.id
    }

    private fun accept(
        target: EvidenceReviewTarget,
        extraction: UUID,
    ) {
        val result =
            bean<EvidenceReviewService>().decide(
                tenant,
                actor,
                target,
                EvidenceReviewCommand(
                    UUID.randomUUID(),
                    extraction,
                    0,
                    EvidenceReviewDecision.ACCEPTED,
                    "Training review",
                ),
            )
        assertThat(result.receipt.reviewer).isEqualTo(actor)
    }

    private fun reviewedBundle(
        claims: List<UUID>,
        procedure: UUID,
    ): EvidenceBundleCommand {
        val contradictions = bean<ContradictionService>()
        val assessment = contradictions.assess(tenant, actor, claims.first())
        assertThat(assessment.findings.map { it.comparison }).containsExactly(ClaimComparison.CONFLICT)
        val selection =
            BundleClaimSetSelection(
                claims.first(),
                assessment.set.revision,
                assessment.set.candidates.map { it.stored.proposal.id },
                null,
            )
        val command =
            EvidenceBundleCommand(
                UUID.randomUUID(),
                listOf(claims.first()),
                listOf(procedure),
                listOf(selection),
            )
        assertThatThrownBy { bean<EvidenceBundleService>().publish(tenant, actor, command) }
            .isInstanceOf(InvalidEvidenceBundle::class.java)
        val decision =
            ContradictionCommand(
                UUID.randomUUID(),
                claims.first(),
                selection.revision,
                selection.candidateIds,
                ContradictionChoice.PREFER_CLAIM,
                claims.first(),
                "Explicit training preference; source integrity is not truth",
            )
        val receipt = contradictions.adjudicate(tenant, actor, decision)
        assertThat(receipt.view.current).isTrue()
        return command.copy(claimSets = listOf(selection.copy(adjudicationId = decision.id)))
    }

    private fun consumeBundle(reference: DocumentaryBundleRequirement) {
        val contract =
            bean<ResolutionContractRevisionService>()
                .publish(tenant, DocumentaryContractFixtures.document(reference.bundleId, reference.sha256))
                .contract
        val commands = bean<CaseCommandService>()
        val case = commands.open(OpenCaseCommand(tenant.value, "Training case", "Locked")).caseId
        commands.pinResolutionContract(
            PinResolutionContractCommand(tenant.value, case, 1, contract.key.value, contract.revision.value),
        )
        val readiness = bean<ResolutionReadinessService>()
        assertThat((readiness.evaluate(tenant.value, case) as CaseReadiness.Evaluated).readiness)
            .isInstanceOf(ResolutionReadiness.DocumentaryEvidenceUnavailable::class.java)
        bean<CaseDocumentaryBundleService>().pin(PinCaseDocumentaryBundleCommand(tenant.value, case, 2, reference))
        assertThat((readiness.evaluate(tenant.value, case) as CaseReadiness.Evaluated).readiness)
            .isInstanceOf(ResolutionReadiness.MissingEvidence::class.java)
        val runs = bean<ResolutionRunService>()
        assertThatThrownBy { runs.start(tenant.value, case, 3) }
            .isInstanceOf(ResolutionRunNotReadyException::class.java)
        commands.recordConnectorObservation(
            RecordConnectorObservationCommand(tenant.value, case, 3, "test", "account", "Locked"),
        )
        val observation = bean<CaseEventStore>().load(tenant, CaseId(case)).last() as ObservationRecorded
        commands.bindAccountAccessState(
            BindAccountAccessStateCommand(
                tenant.value,
                case,
                4,
                observation.observationId,
                org.ergon.cases.domain.AccountAccessState.LOCKED,
            ),
        )
        assertThat((readiness.evaluate(tenant.value, case) as CaseReadiness.Evaluated).readiness)
            .isInstanceOf(ResolutionReadiness.Ready::class.java)
        assertThat(runs.start(tenant.value, case, 5).run.initialState)
            .isEqualTo(ResolutionRunInitialState.WAITING_FOR_APPROVAL)
        assertNoExecutionArtifacts(case)
    }

    private fun assertNoExecutionArtifacts(case: UUID) {
        val hasArtifacts =
            jdbc
                .sql(
                    """
                    SELECT EXISTS (SELECT 1 FROM capability_authorization_grants WHERE tenant_id = :tenant)
                        OR EXISTS (SELECT 1 FROM capability_invocation_receipts WHERE tenant_id = :tenant)
                        OR EXISTS (SELECT 1 FROM resolution_outcome_proof_acceptances WHERE tenant_id = :tenant)
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .query(Boolean::class.java)
                .single()
        assertThat(hasArtifacts).isFalse()
        assertThat(
            jdbc
                .sql("SELECT status FROM cases WHERE tenant_id = :tenant AND case_id = :case")
                .param("tenant", tenant.value)
                .param("case", case)
                .query(String::class.java)
                .single(),
        ).isEqualTo("OPEN")
    }

    private fun seedReviewer() {
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'acceptance', :subject, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("actor", actor.value)
            .param("subject", actor.value.toString())
            .update()
        jdbc
            .sql(
                """
                INSERT INTO approval_authority_evidence
                    (tenant_id, evidence_id, actor_id, authority, source_provider, source_reference, attested_at, expires_at)
                VALUES (:tenant, :id, :actor, 'RESOLVER', 'acceptance', 'training',
                    clock_timestamp() - INTERVAL '60 seconds', clock_timestamp() + INTERVAL '1 hour')
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", UUID.randomUUID())
            .param("actor", actor.value)
            .update()
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
