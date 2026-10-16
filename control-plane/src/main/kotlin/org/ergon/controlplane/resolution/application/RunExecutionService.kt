package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import java.time.Clock

/**
 * Runs only a newly committed admission; replay merely observes its stored result.
 * Connector I/O occurs outside the admission and receipt/projection transactions.
 * Only connector exceptions become uncertainty. Persistence/invariant failures
 * propagate, retaining the already committed reservation or terminal receipt.
 */
@Suppress("LongParameterList") // Existing reservation, receipt and result owners stay explicit.
class RunExecutionService(
    private val admissions: RunExecutionAdmissionService,
    private val invocation: CapabilityInvocationService,
    private val results: ResolutionRunCapabilityResultService,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val authorities: HumanAuthorityRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** First admission may invoke; identical command replay never does, even while incomplete. */
    fun execute(command: ExecuteRunCommand): RunExecutionAcknowledgement = finish(admissions.admit(command))

    /** A distinct explicitly confirmed attempt may invoke only the original reserved operation. */
    fun resume(command: ResumeRunExecutionCommand): RunExecutionAcknowledgement = finish(admissions.resume(command))

    private fun finish(admission: RunExecutionAdmission): RunExecutionAcknowledgement {
        val stored = admission.stored
        val command = stored.command
        if (admission.dispatch) {
            try {
                invocation.invoke(command.tenantId, stored.consumptionId.value)
            } catch (_: RunExecutionConnectorUncertainException) {
                return acknowledge(stored)
            }
            results.record(command.tenantId, command.runId)
        }
        return acknowledge(stored)
    }

    private fun acknowledge(stored: StoredRunExecutionCommand): RunExecutionAcknowledgement =
        transactions.required {
            val tenant = TenantId(stored.command.tenantId)
            val actor = HumanActorId(stored.command.actorId)
            // Admission remains durable if authority expires during I/O, but that
            // expired identity must not receive a new private acknowledgement.
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant()) == null) {
                throw AssignedRunNotFoundException()
            }
            val receipt = receipts.find(tenant, stored.consumptionId)
            val event = transitions.findByReceipt(tenant, stored.consumptionId)
            val status =
                when {
                    event != null -> RunExecutionStatus.COMPLETED
                    receipt != null -> RunExecutionStatus.RESULT_RECORDED
                    else -> RunExecutionStatus.INCOMPLETE
                }
            RunExecutionAcknowledgement(stored, status, event)
        }
}

/** Isolates thrown connector outcomes from database/invariant errors in the reused invocation service. */
class UncertainCapabilityConnectorGateway(
    private val delegate: CapabilityConnectorGateway,
) : CapabilityConnectorGateway {
    // The connector port permits arbitrary provider exceptions. Classify them
    // once here; persistence failures outside this call must still propagate.
    @Suppress("TooGenericExceptionCaught")
    override fun invoke(command: CapabilityInvocationCommand) =
        try {
            delegate.invoke(command)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RunExecutionConnectorUncertainException(failure)
        } catch (failure: Exception) {
            throw RunExecutionConnectorUncertainException(failure)
        }
}
