package org.ergon.controlplane.resolution.adapter.out.connector

import org.ergon.controlplane.resolution.application.CapabilityConnectorGateway
import org.ergon.controlplane.resolution.application.CapabilityConnectorUnavailableException
import org.ergon.controlplane.resolution.application.CapabilityInvocationCommand
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.ergon.resolution.domain.ProviderOperationReference
import org.springframework.stereotype.Component

/** Deterministic local connector for the first access-restoration execution slice. */
@Component
class FakeIdentityCapabilityConnector : CapabilityConnectorGateway {
    /**
     * Simulates an idempotent account unlock without network or credential dependencies.
     *
     * The provider reference is a pure function of the idempotency key, so a
     * retry after an uncertain process failure observes the same operation.
     *
     * @throws CapabilityConnectorUnavailableException when durable routing
     *   selects anything except the supported connector and capability pair.
     */
    override fun invoke(command: CapabilityInvocationCommand): CapabilityInvocationResult {
        val consumption = command.consumption
        if (
            consumption.connector.value != CONNECTOR ||
            consumption.capability.value != UNLOCK_CAPABILITY
        ) {
            throw CapabilityConnectorUnavailableException(consumption.connector.value)
        }
        return CapabilityInvocationResult(
            outcome = CapabilityInvocationOutcome.SUCCEEDED,
            providerOperationReference =
                ProviderOperationReference.of("identity-stub/operations/${command.idempotencyKey}"),
        )
    }

    private companion object {
        const val CONNECTOR = "identity-stub"
        const val UNLOCK_CAPABILITY = "identity.account.unlock"
    }
}
