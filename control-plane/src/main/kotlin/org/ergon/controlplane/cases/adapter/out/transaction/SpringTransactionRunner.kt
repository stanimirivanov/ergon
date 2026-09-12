package org.ergon.controlplane.cases.adapter.out.transaction

import org.ergon.controlplane.cases.application.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Implements [TransactionRunner] with Spring's [TransactionTemplate].
 *
 * The template must retain `PROPAGATION_REQUIRED`; changing its propagation
 * would violate the application port's nesting contract.
 */
@Component
class SpringTransactionRunner(
    private val transactionTemplate: TransactionTemplate,
) : TransactionRunner {
    override fun <T : Any> required(block: () -> T): T =
        checkNotNull(transactionTemplate.execute { block() }) { "transaction returned no result" }
}
