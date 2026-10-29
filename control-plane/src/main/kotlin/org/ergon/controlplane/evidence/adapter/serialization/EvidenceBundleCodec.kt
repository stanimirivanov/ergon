package org.ergon.controlplane.evidence.adapter.serialization

import com.fasterxml.jackson.annotation.JsonInclude
import org.ergon.evidence.domain.BundleClaimSet
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.BundleProposalReference
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.DocumentaryClaimKey
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceBundleManifest
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.evidence.domain.SourceDigest
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.TenantId
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Public manifest schema v1 is also the canonical immutable storage representation; order is declared here. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class BundleProposalDefinition(
    val kind: String,
    val id: UUID,
    val sourceId: UUID,
    val digest: String,
    val extractionId: UUID,
    val compilerRevision: String,
    val reviewCommandId: UUID,
    val selected: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BundleSourceDefinition(
    val sourceId: UUID,
    val digest: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BundleSetDefinition(
    val property: String,
    val subject: String,
    val scope: String,
    val revision: Long,
    val candidateIds: List<UUID>,
    val reviewCommandIds: List<UUID>,
    val findingCodes: List<String>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val adjudicationId: UUID?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BundleManifestDefinition(
    val schema: String,
    val tenantId: UUID,
    val assessedAt: String,
    val proposals: List<BundleProposalDefinition>,
    val sources: List<BundleSourceDefinition>,
    val claimSets: List<BundleSetDefinition>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BundleSetCommandDefinition(
    val anchorId: UUID,
    val revision: Long,
    val candidateIds: List<UUID>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS) val adjudicationId: UUID?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BundleCommandDefinition(
    val claims: List<UUID>,
    val procedures: List<UUID>,
    val claimSets: List<BundleSetCommandDefinition>,
)

/**
 * Canonical v1 uses an isolated mapper, independent of global HTTP serializer settings.
 * Invalid/unsupported stored data is an integrity failure, never client absence.
 */
@Component
class EvidenceBundleCodec {
    private val json =
        JsonMapper
            .builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            .build()
    private val strict =
        json
            .reader()
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun encode(
        tenant: TenantId,
        manifest: EvidenceBundleManifest,
    ): String = json.writeValueAsString(definition(tenant, manifest))

    fun definition(
        tenant: TenantId,
        manifest: EvidenceBundleManifest,
    ): BundleManifestDefinition {
        val content = manifest.content
        require(content.sources.all { it.tenantId == tenant })
        return BundleManifestDefinition(
            EvidenceBundleManifest.SCHEMA,
            tenant.value,
            manifest.assessedAt.toString(),
            content.proposals.map {
                BundleProposalDefinition(
                    it.target.kind.name,
                    it.target.id,
                    it.extraction.source.sourceId.value,
                    it.extraction.source.digest.value,
                    it.extraction.id,
                    it.extraction.extractorRevision,
                    it.reviewCommandId,
                    it.selected,
                )
            },
            content.sources.map { BundleSourceDefinition(it.sourceId.value, it.digest.value) },
            content.claimSets.map { setDefinition(it) },
        )
    }

    fun groups(content: EvidenceBundleContent): String {
        val groups = content.claimSets.map { setDefinition(it) }
        return json.writeValueAsString(groups)
    }

    fun encodeCommand(command: EvidenceBundleCommand): String =
        json.writeValueAsString(
            BundleCommandDefinition(
                command.claims,
                command.procedures,
                command.claimSets.map {
                    BundleSetCommandDefinition(it.anchorId, it.revision, it.candidateIds, it.adjudicationId)
                },
            ),
        )

    fun decodeCommand(
        id: UUID,
        text: String,
    ): EvidenceBundleCommand =
        stored {
            val input = strict.forType(BundleCommandDefinition::class.java).readValue<BundleCommandDefinition>(text)
            EvidenceBundleCommand(
                id,
                input.claims,
                input.procedures,
                input.claimSets.map {
                    BundleClaimSetSelection(it.anchorId, it.revision, it.candidateIds, it.adjudicationId)
                },
            )
        }

    fun decode(
        tenant: TenantId,
        text: String,
    ): EvidenceBundleManifest =
        stored {
            val input = strict.forType(BundleManifestDefinition::class.java).readValue<BundleManifestDefinition>(text)
            check(input.schema == EvidenceBundleManifest.SCHEMA && input.tenantId == tenant.value) {
                "unsupported bundle scope/schema"
            }
            EvidenceBundleManifest(
                Instant.parse(input.assessedAt),
                EvidenceBundleContent(
                    input.proposals.map {
                        val source = SourceRevision(tenant, SourceId(it.sourceId), SourceDigest(it.digest))
                        BundleProposalReference(
                            EvidenceReviewTarget(EvidenceProposalKind.valueOf(it.kind), it.id),
                            EvidenceExtraction(it.extractionId, source, it.compilerRevision),
                            it.reviewCommandId,
                            it.selected,
                        )
                    },
                    input.sources.map { SourceRevision(tenant, SourceId(it.sourceId), SourceDigest(it.digest)) },
                    input.claimSets.map {
                        BundleClaimSet(
                            DocumentaryClaimKey(DocumentaryProperty.valueOf(it.property), it.subject, it.scope),
                            it.revision,
                            it.candidateIds,
                            it.reviewCommandIds,
                            it.findingCodes.map(ClaimComparison::valueOf),
                            it.adjudicationId,
                        )
                    },
                ),
            )
        }

    private fun setDefinition(set: BundleClaimSet) =
        BundleSetDefinition(
            set.key.property.name,
            set.key.subject,
            set.key.scope,
            set.revision,
            set.candidateIds,
            set.reviewCommandIds,
            set.findingCodes.map { it.name },
            set.adjudicationId,
        )

    private fun <T> stored(block: () -> T): T =
        try {
            block()
        } catch (
            error: JacksonException,
        ) {
            invalidStored(error)
        } catch (
            error: IllegalArgumentException,
        ) {
            invalidStored(error)
        } catch (error: java.time.DateTimeException) {
            invalidStored(error)
        }

    private fun invalidStored(error: Exception): Nothing =
        throw IllegalStateException(
            "invalid stored bundle representation",
            error,
        )
}

internal fun bundleDigest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
