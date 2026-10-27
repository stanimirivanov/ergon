package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseGoal
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.ObservationId
import org.ergon.cases.domain.ObservationOrigin
import org.ergon.cases.domain.SourceObservation
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Caller supplies a verified registered tenant actor; [caseId] is the client's stable recovery identity. */
data class RequesterCaseIntakeCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val requesterActorId: UUID,
    val goal: String,
    val initialObservation: String,
)

/** Original normalized input and opening times, never current lifecycle, approval or execution authority. */
data class RequesterCaseIntakeReceipt(
    val caseId: UUID,
    val goal: String,
    val initialObservation: String,
    val openedAt: Instant,
    val recordedAt: Instant,
)

data class RequesterCaseIntakeResult(
    val receipt: RequesterCaseIntakeReceipt,
    val created: Boolean,
)

/** Null actor means the opening was not attributed by this intake producer, including legacy bootstrap. */
data class StoredRequesterCaseOpening(
    val opening: CaseOpened,
    val requesterActorId: HumanActorId?,
    val recordedAt: Instant,
)

/** Intake consumes the immutable opening and ownership, not a mutable case projection or entire history. */
interface RequesterCaseIntakeRepository {
    /** Serialize against every case writer using its stream lock; caller owns the required transaction. */
    fun lock(
        tenantId: TenantId,
        caseId: CaseId,
    )

    /** Returns the first opening or null for an absent stream; storage/codec failures must propagate. */
    fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): StoredRequesterCaseOpening?

    /** Insert the verified creator with intake provenance after projection, in the same transaction; never adopt an old case. */
    fun bind(
        tenantId: TenantId,
        caseId: CaseId,
        actorId: HumanActorId,
    )
}

/** Deliberately discloses neither an existing owner nor an opening's private input. */
class RequesterCaseIntakeConflictException : RuntimeException("Requester intake intent conflicts")

/** Opens an owned case atomically, without selecting a contract, starting a run or granting approval. */
class RequesterCaseIntakeService(
    private val repository: RequesterCaseIntakeRepository,
    private val committer: CaseEventCommitter,
    private val identities: IdentityGenerator,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * The caller must authenticate/resolve [RequesterCaseIntakeCommand.requesterActorId] before invocation.
     * Goal/observation keep existing domain trimming and UTF-16 limits; NUL and malformed Unicode are rejected.
     * The tenant/case lock covers lookup, event/projection creation and binding, including concurrent recovery.
     * Exact normalized recovery returns the original opening even after case closure; it never restores access.
     * @throws RequesterCaseIntakeConflictException for different intent/actor or a non-intake opening.
     * @throws IllegalArgumentException for invalid input. Storage failures roll back every write and propagate.
     */
    fun open(command: RequesterCaseIntakeCommand): RequesterCaseIntakeResult {
        val goal = CaseGoal.of(command.goal)
        val observation =
            SourceObservation.create(
                ObservationId(identities.next()),
                ObservationOrigin.requesterApi(),
                command.initialObservation,
                clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        require(
            listOf(goal.value, observation.content).all { text ->
                text.codePoints().allMatch { it != 0 && it !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code }
            },
        ) { "Requester intake text must be valid Unicode without NUL" }
        val tenant = TenantId(command.tenantId)
        val case = CaseId(command.caseId)
        val actor = HumanActorId(command.requesterActorId)
        return transactions.required {
            repository.lock(tenant, case)
            val existing = repository.find(tenant, case)
            val created = existing == null
            if (created) {
                committer.commit(ErgonCase.open(case, tenant, goal, observation), expectedVersion = 0)
                repository.bind(tenant, case, actor)
            }
            val stored = existing ?: checkNotNull(repository.find(tenant, case))
            if (stored.requesterActorId != actor || stored.opening.goal != goal.value ||
                stored.opening.observationContent != observation.content
            ) {
                throw RequesterCaseIntakeConflictException()
            }
            RequesterCaseIntakeResult(
                RequesterCaseIntakeReceipt(
                    case.value,
                    stored.opening.goal,
                    stored.opening.observationContent,
                    stored.opening.occurredAt,
                    stored.recordedAt,
                ),
                created,
            )
        }
    }
}
