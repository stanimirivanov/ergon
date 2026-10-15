package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.RunOutcomeVerification
import org.ergon.controlplane.resolution.application.RunProofAcceptance
import org.ergon.controlplane.resolution.application.RunVerificationEvidence
import java.time.Instant
import java.util.UUID

/** Sanitized single-condition verification; MATCHING is mutable, ACCEPTED is a durable runtime decision. */
data class BrowserRunOutcomeVerification(
    val status: String,
    val property: String,
    val expectedValue: String,
    val caseStreamVersion: Long?,
    val reason: String?,
    val evidence: BrowserRunVerificationEvidence?,
    val acceptance: BrowserRunProofAcceptance?,
)

/** Only the selected post-action binding/source, not all evidence after the run's pin. */
data class BrowserRunVerificationEvidence(
    val factId: UUID,
    val streamVersion: Long,
    val value: String,
    val accountReference: String,
    val boundAt: Instant,
    val source: BrowserRunProofSource,
)

/** Source occurrence is preserved; database recording time is not supplied by the case event read port. */
data class BrowserRunProofSource(
    val observationId: UUID,
    val streamVersion: Long,
    val originType: String,
    val provider: String,
    val reference: String?,
    val content: String,
    val observedAt: Instant,
)

data class BrowserRunProofAcceptance(
    val runSequence: Long,
    val acceptedAt: Instant,
    val recordedAt: Instant,
    val caseClosureStreamVersion: Long,
)

internal fun RunOutcomeVerification.toBrowserVerification(): BrowserRunOutcomeVerification =
    BrowserRunOutcomeVerification(
        status.name,
        condition.fact.value,
        condition.expectedValue.value,
        caseStreamVersion,
        reason,
        evidence?.toBrowserProofEvidence(),
        acceptance?.toBrowserAcceptance(),
    )

private fun RunVerificationEvidence.toBrowserProofEvidence(): BrowserRunVerificationEvidence =
    BrowserRunVerificationEvidence(
        fact.factId.value,
        fact.factStreamVersion,
        fact.value.value,
        accountReference,
        fact.boundAt,
        BrowserRunProofSource(
            source.observationId,
            fact.observationStreamVersion,
            source.observationOriginType.name,
            source.observationProvider,
            source.observationReference,
            source.observationContent,
            source.occurredAt,
        ),
    )

private fun RunProofAcceptance.toBrowserAcceptance(): BrowserRunProofAcceptance =
    BrowserRunProofAcceptance(runSequence, acceptedAt, recordedAt, caseClosureStreamVersion)
