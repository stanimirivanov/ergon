package org.ergon.controlplane

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ControlPlaneApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<ControlPlaneApplication>(*args)
}
