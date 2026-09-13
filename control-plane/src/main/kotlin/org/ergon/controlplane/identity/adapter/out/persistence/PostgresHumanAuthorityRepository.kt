package org.ergon.controlplane.identity.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.identity.application.HumanActorIdentityAlreadyExistsException
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.identity.domain.HumanActor
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL adapter for immutable, tenant-scoped human identity bindings.
 *
 * [create] takes a transaction-scoped advisory lock on the normalized external identity
 * before checking uniqueness. This preserves a stable domain conflict under concurrent
 * registration instead of exposing a database constraint failure.
 */
@Repository
class PostgresHumanAuthorityRepository(
    private val jdbcClient: JdbcClient,
) : HumanAuthorityRepository {
    override fun create(
        tenantId: TenantId,
        actor: HumanActor,
        registeredAt: Instant,
    ): StoredHumanActor {
        lockExternalIdentity(tenantId, actor.identityProvider, actor.subject)
        findIdByExternalIdentity(tenantId, actor.identityProvider, actor.subject)?.let { existingId ->
            throw HumanActorIdentityAlreadyExistsException(existingId)
        }
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO human_actors (
                        tenant_id, actor_id, identity_provider, identity_subject, registered_at
                    ) VALUES (
                        :tenantId, :actorId, :identityProvider, :identitySubject, :registeredAt
                    )
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("actorId", actor.id.value)
                .param("identityProvider", actor.identityProvider)
                .param("identitySubject", actor.subject)
                .param("registeredAt", registeredAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .single()
                .toInstant()
        return StoredHumanActor(actor, registeredAt, recordedAt)
    }

    override fun find(
        tenantId: TenantId,
        actorId: HumanActorId,
    ): StoredHumanActor? =
        jdbcClient
            .sql(
                """
                SELECT
                    actor_id,
                    identity_provider,
                    identity_subject,
                    registered_at,
                    recorded_at
                FROM human_actors
                WHERE tenant_id = :tenantId AND actor_id = :actorId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("actorId", actorId.value)
            .query { resultSet, _ ->
                val actor =
                    HumanActor.create(
                        id = HumanActorId(resultSet.getObject("actor_id", UUID::class.java)),
                        identityProvider = resultSet.getString("identity_provider"),
                        subject = resultSet.getString("identity_subject"),
                    )
                StoredHumanActor(
                    actor = actor,
                    registeredAt = resultSet.getObject("registered_at", OffsetDateTime::class.java).toInstant(),
                    recordedAt = resultSet.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    private fun findIdByExternalIdentity(
        tenantId: TenantId,
        identityProvider: String,
        identitySubject: String,
    ): UUID? =
        jdbcClient
            .sql(
                """
                SELECT actor_id
                FROM human_actors
                WHERE tenant_id = :tenantId
                    AND identity_provider = :identityProvider
                    AND identity_subject = :identitySubject
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("identityProvider", identityProvider)
            .param("identitySubject", identitySubject)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun lockExternalIdentity(
        tenantId: TenantId,
        identityProvider: String,
        identitySubject: String,
    ) {
        // The service transaction holds this lock through the subsequent uniqueness check and insert.
        jdbcClient
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:identityKey, 0))")
            .param("identityKey", "${tenantId.value}:$identityProvider:$identitySubject")
            .query { _, _ -> Unit }
            .single()
    }

    // Authority evidence shares this adapter so actor lookup and attestation writes use one port.
    override fun create(
        tenantId: TenantId,
        evidence: ApprovalAuthorityEvidence,
    ): StoredApprovalAuthorityEvidence {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO approval_authority_evidence (
                        tenant_id, evidence_id, actor_id, authority, case_id,
                        source_provider, source_reference, attested_at, expires_at
                    ) VALUES (
                        :tenantId, :evidenceId, :actorId, :authority, :caseId,
                        :sourceProvider, :sourceReference, :attestedAt, :expiresAt
                    )
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("evidenceId", evidence.id.value)
                .param("actorId", evidence.actorId.value)
                .param("authority", evidence.authority.name)
                .param("caseId", evidence.caseId?.value)
                .param("sourceProvider", evidence.source.provider)
                .param("sourceReference", evidence.source.reference)
                .param("attestedAt", evidence.attestedAt.atOffset(ZoneOffset.UTC))
                .param("expiresAt", evidence.expiresAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .single()
                .toInstant()
        return StoredApprovalAuthorityEvidence(evidence, recordedAt)
    }

    override fun find(
        tenantId: TenantId,
        evidenceId: ApprovalAuthorityEvidenceId,
    ): StoredApprovalAuthorityEvidence? =
        jdbcClient
            .sql(
                """
                SELECT
                    evidence_id,
                    actor_id,
                    authority,
                    case_id,
                    source_provider,
                    source_reference,
                    attested_at,
                    expires_at,
                    recorded_at
                FROM approval_authority_evidence
                WHERE tenant_id = :tenantId AND evidence_id = :evidenceId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("evidenceId", evidenceId.value)
            .query { resultSet, _ ->
                val caseId = resultSet.getObject("case_id", UUID::class.java)?.let(::CaseId)
                val attestation =
                    ApprovalAuthorityEvidence(
                        id = ApprovalAuthorityEvidenceId(resultSet.getObject("evidence_id", UUID::class.java)),
                        actorId = HumanActorId(resultSet.getObject("actor_id", UUID::class.java)),
                        authority = ApprovalAuthority.valueOf(resultSet.getString("authority")),
                        caseId = caseId,
                        source =
                            ApprovalAuthorityEvidenceSource.create(
                                resultSet.getString("source_provider"),
                                resultSet.getString("source_reference"),
                            ),
                        attestedAt = resultSet.getObject("attested_at", OffsetDateTime::class.java).toInstant(),
                        expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                    )
                StoredApprovalAuthorityEvidence(
                    evidence = attestation,
                    recordedAt = resultSet.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)
}
