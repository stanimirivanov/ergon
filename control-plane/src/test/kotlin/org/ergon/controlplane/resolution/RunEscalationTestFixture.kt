package org.ergon.controlplane.resolution

import org.ergon.controlplane.resolution.application.EscalateSupervisedRunCommand
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wire inputs and coordinated command drivers, separated from integration assertions.
 * Owns neither container lifecycle nor production seeds.
 */
internal object RunEscalationTestFixture {
    fun policy(ceiling: Int = 2): ResolutionRetryPolicy =
        ResolutionRetryPolicy.define(
            ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v1"),
            ceiling,
        )

    fun session(actor: UUID) =
        oidcLogin().idToken { token ->
            token.issuer("https://identity.example.test").subject("resolver-$actor")
        }

    fun base(command: EscalateSupervisedRunCommand): String =
        "/bff/v1/tenants/${command.tenantId}" +
            "/resolution-runs/${command.runId}"

    fun body(command: EscalateSupervisedRunCommand): String =
        """{"commandId":"${command.commandId}",
        "expectedSupervisionRevision":${command.expectedSupervisionRevision.value},
        "expectedRunVersion":${command.expectedRunVersion},
        "sourceAttemptNumber":${command.sourceAttemptNumber},
        "retryPolicyRevision":"${command.retryPolicyRevision}",
        "maximumAttempts":${command.maximumAttempts},"reason":"${command.reason}"}"""

    fun <T> together(
        first: () -> T,
        second: () -> T,
    ): List<T> =
        Executors.newFixedThreadPool(2).use { pool ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val tasks =
                listOf(first, second).map { action ->
                    pool.submit<T> {
                        ready.countDown()
                        check(start.await(15, TimeUnit.SECONDS))
                        action()
                    }
                }
            try {
                check(ready.await(15, TimeUnit.SECONDS))
            } finally {
                start.countDown()
            }
            tasks.map { it.get(30, TimeUnit.SECONDS) }
        }
}
