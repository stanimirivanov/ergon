package org.ergon.controlplane.contracts.application

import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.identity.domain.TenantId
import java.time.Instant

/** One immutable contract revision with its database-assigned recording time. */
data class StoredResolutionContractRevision(
    val contract: ResolutionContract,
    val recordedAt: Instant,
)

/** Tenant-scoped durable storage for immutable resolution contract revisions. */
interface ResolutionContractRevisionRepository {
    /**
     * Publishes [contract] exactly once within [tenantId].
     *
     * Implementations must atomically reject an existing key/revision pair and
     * must not replace or merge its stored meaning.
     *
     * @throws ContractRevisionAlreadyExistsException when the tenant already
     *   owns the same contract key and revision.
     */
    fun publish(
        tenantId: TenantId,
        contract: ResolutionContract,
    ): StoredResolutionContractRevision

    /**
     * @return the exact stored revision, or `null` when it does not exist in
     *   [tenantId]. Infrastructure failures still surface as exceptions.
     */
    fun find(
        tenantId: TenantId,
        key: ResolutionContractKey,
        revision: ResolutionContractRevision,
    ): StoredResolutionContractRevision?
}

/** Signals an attempted replacement of an immutable tenant contract revision. */
class ContractRevisionAlreadyExistsException(
    key: ResolutionContractKey,
    revision: ResolutionContractRevision,
) : RuntimeException("resolution contract ${key.value} revision ${revision.value} already exists")

/** Signals that an exact tenant-scoped contract revision cannot be found. */
class ContractRevisionNotFoundException(
    key: ResolutionContractKey,
    revision: ResolutionContractRevision,
) : RuntimeException("resolution contract ${key.value} revision ${revision.value} was not found")

/** Validates, publishes, and retrieves immutable tenant contract revisions. */
class ResolutionContractRevisionService(
    private val decoder: ResolutionContractDocumentDecoder,
    private val referenceValidator: ResolutionContractReferenceValidator,
    private val repository: ResolutionContractRevisionRepository,
) {
    /**
     * Validates [document] and all semantic references before atomically
     * publishing its normalized meaning.
     *
     * @throws InvalidResolutionContractDocumentException when the document is invalid.
     * @throws UnregisteredContractReferencesException when a fact
     *   type or capability is not registered.
     * @throws ContractRevisionAlreadyExistsException when the revision already exists.
     */
    fun publish(
        tenantId: TenantId,
        document: String,
    ): StoredResolutionContractRevision {
        val contract = decoder.decode(document)
        referenceValidator.validate(contract)
        return repository.publish(tenantId, contract)
    }

    /**
     * Returns the exact immutable revision owned by [tenantId].
     *
     * @throws ContractRevisionNotFoundException when the revision does not exist
     *   in that tenant boundary.
     */
    fun get(
        tenantId: TenantId,
        key: ResolutionContractKey,
        revision: ResolutionContractRevision,
    ): StoredResolutionContractRevision =
        repository.find(tenantId, key, revision)
            ?: throw ContractRevisionNotFoundException(key, revision)
}
