package org.ergon.tools.repositorypolicy

import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

/** Runs Ergon's deterministic repository policy against a checked-out repository root. */
fun main(arguments: Array<String>) {
    val root =
        arguments
            .singleOrNull()
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
    if (root == null) {
        System.err.println("Usage: repository-policy <repository-root>")
        exitProcess(2)
    }

    val violations =
        try {
            RepositoryPolicy.check(root)
        } catch (exception: IOException) {
            System.err.println("Repository policy could not inspect $root: ${exception.message}")
            exitProcess(2)
        } catch (exception: IllegalArgumentException) {
            System.err.println("Repository policy could not inspect $root: ${exception.message}")
            exitProcess(2)
        }

    if (violations.isEmpty()) {
        println("Repository policy passed for $root")
        return
    }

    System.err.println("Repository policy found ${violations.size} violation(s):")
    violations.forEach { violation -> System.err.println("\n$violation") }
    exitProcess(1)
}
