package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.ProcedureCommand
import org.ergon.controlplane.evidence.application.ProcedureOperationBinding
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkProcedureOutline
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkSourceParser
import org.ergon.controlplane.knowledge.adapter.inbound.document.OpenApiSourceParser
import org.ergon.evidence.domain.DocumentedOperation
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.ProcedureContent
import org.ergon.evidence.domain.ProcedureProposal
import org.ergon.evidence.domain.ProcedureStep
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceObservationKind
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.util.UUID

internal class ProcedureFixtures {
    val tenant = TenantId(UUID(0, 1))
    val actor = HumanActorId(UUID(0, 2))
    val markdown =
        EvidenceSource.fromUtf8(
            tenant,
            SourceId(UUID(0, 3)),
            EvidenceSourceFormat.COMMONMARK,
            (
                "# Recovery 😀\r\n\r\n3. Read 😀\r\n   details.\r\n" +
                    "4. Call [help](https://invalid.example) <b>carefully</b>"
            ).toByteArray(),
        )
    val api =
        EvidenceSource.fromUtf8(
            tenant,
            SourceId(UUID(0, 4)),
            EvidenceSourceFormat.OPENAPI_3_0_JSON,
            """
            {"openapi":"3.0.3","info":{"title":"Test","version":"1"},
             "paths":{"/account":{"post":{"responses":{"200":{"description":"OK"}}}}}}
            """.trimIndent()
                .toByteArray(),
        )
    val compiled = CommonMarkSourceParser().parse(markdown)
    val compiledApi = OpenApiSourceParser().parse(api)
    val listIndex = compiled.observations.indexOfFirst { it.kind == SourceObservationKind.ORDERED_LIST }
    val operationIndex = compiledApi.observations.indexOfFirst { it.kind == SourceObservationKind.API_OPERATION }
    val command =
        ProcedureCommand(
            UUID(0, 5),
            compiled.extraction.id,
            listIndex,
            "Restore access",
            listOf(ProcedureOperationBinding(1, api.revision, compiledApi.extraction.id, operationIndex)),
        )

    fun proposal(command: ProcedureCommand = this.command): ProcedureProposal {
        val list = compiled.observations[command.observationIndex].citation
        val operation = compiledApi.observations[operationIndex]
        val selected =
            DocumentedOperation(
                compiledApi.extraction,
                operationIndex,
                operation.citation,
                checkNotNull(operation.jsonPointer),
            )
        val steps =
            CommonMarkProcedureOutline().steps(markdown, list).mapIndexed { index, citation ->
                val linked = command.operations.any { it.position == index + 1 }
                ProcedureStep(index + 1, citation, if (linked) selected else null)
            }
        return ProcedureProposal(
            command.id,
            compiled.extraction,
            command.observationIndex,
            ProcedureContent.create(command.title, list, steps),
        )
    }
}
