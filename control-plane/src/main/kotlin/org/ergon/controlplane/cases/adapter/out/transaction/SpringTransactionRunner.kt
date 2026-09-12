package org.ergon.controlplane.cases.adapter.out.transaction

import org.ergon.controlplane.cases.application.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Spring adapter for application-requested local database transactions. */
@Component
class SpringTransactionRunner(
    private val transactionTemplate: TransactionTemplate,
) : TransactionRunner {
    override fun <T : Any> required(block: () -> T): T =
        checkNotNull(transactionTemplate.execute { block() }) { "transaction returned no result" }
}
