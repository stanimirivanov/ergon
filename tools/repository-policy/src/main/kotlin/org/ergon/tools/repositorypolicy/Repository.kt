package org.ergon.tools.repositorypolicy

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

private val EXCLUDED_DIRECTORIES =
    setOf(".git", ".idea", ".local", ".m2", ".codex", "node_modules", "target", "testdata", "vendor")

internal class Repository private constructor(
    val root: Path,
    val documents: Map<String, MarkdownDocument>,
    val entries: Set<String>,
) {
    fun document(path: String): MarkdownDocument? = documents[path]

    companion object {
        fun load(root: Path): Repository {
            val normalizedRoot = root.toAbsolutePath().normalize()
            require(Files.isDirectory(normalizedRoot)) { "repository root does not exist: $normalizedRoot" }

            val paths =
                Files
                    .walk(normalizedRoot)
                    .use { stream ->
                        stream
                            .filter { path -> path != normalizedRoot }
                            .filter { path -> !path.hasExcludedSegment(normalizedRoot) }
                            .sorted()
                            .toList()
                    }
            val entries = paths.mapTo(sortedSetOf()) { normalizedRoot.relativeUnix(it) }
            val documents =
                paths
                    .asSequence()
                    .filter { !it.isDirectory() && it.fileName.toString().endsWith(".md", ignoreCase = true) }
                    .associate { path ->
                        val relative = normalizedRoot.relativeUnix(path)
                        relative to MarkdownDocument.parse(relative, Files.readString(path, StandardCharsets.UTF_8))
                    }.toSortedMap()

            return Repository(normalizedRoot, documents, entries)
        }

        fun fixture(
            root: Path,
            documents: Map<String, String>,
            additionalEntries: Set<String> = emptySet(),
        ): Repository {
            val parsed = documents.mapValues { (path, body) -> MarkdownDocument.parse(path, body) }.toSortedMap()
            return Repository(
                root = root.toAbsolutePath().normalize(),
                documents = parsed,
                entries = (documents.keys + additionalEntries).toSortedSet(),
            )
        }
    }
}

private fun Path.hasExcludedSegment(root: Path): Boolean =
    root
        .relativize(this)
        .any { segment -> segment.toString() in EXCLUDED_DIRECTORIES }

private fun Path.relativeUnix(path: Path): String = relativize(path).joinToString("/") { it.toString() }
