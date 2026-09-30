package org.ergon.tools.repositorypolicy

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal object LinkPolicy {
    private const val POLICY = "docs/development/harness.md#repository-policy"
    private val externalScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun check(repository: Repository): List<Violation> =
        repository.documents.values.flatMap { document ->
            document.links.mapNotNull { link -> validate(repository, document, link) }
        }

    private fun validate(
        repository: Repository,
        source: MarkdownDocument,
        link: MarkdownLink,
    ): Violation? {
        val destination = link.destination.trim().removeSurrounding("<", ">")
        var result: Violation? = null
        if (!isExternalOrEmpty(destination)) {
            val rawPath = destination.substringBefore('#').substringBefore('?')
            val rawFragment = destination.substringAfter('#', "").substringBefore('?')
            val targetPath = if (rawPath.isEmpty()) source.relativePath else resolve(source.relativePath, rawPath)
            val target = targetPath?.let { LinkTarget(source, link, destination, it) }
            result =
                when {
                    target == null -> {
                        containmentViolation(source, link, destination)
                    }

                    target.path !in repository.entries -> {
                        missingTarget(repository, target)
                    }

                    rawFragment.isEmpty() -> {
                        null
                    }

                    else -> {
                        invalidAnchor(repository, target, rawFragment)
                    }
                }
        }
        return result
    }

    private fun isExternalOrEmpty(destination: String): Boolean =
        destination.isEmpty() || externalScheme.containsMatchIn(destination) || destination.startsWith("//")

    private fun containmentViolation(
        source: MarkdownDocument,
        link: MarkdownLink,
        destination: String,
    ): Violation =
        violation(
            source,
            link,
            "markdown.link.containment",
            "local link `$destination` escapes the repository or uses an absolute path",
            "link to a repository-relative destination contained below the repository root",
        )

    private fun missingTarget(
        repository: Repository,
        target: LinkTarget,
    ): Violation {
        val caseMatch = repository.entries.firstOrNull { it.equals(target.path, ignoreCase = true) }
        return if (caseMatch != null) {
            violation(
                target.source,
                target.link,
                "markdown.link.casing",
                "local link `${target.destination}` does not use the checked-in path casing `$caseMatch`",
                "change the destination to the exact checked-in path `$caseMatch`",
            )
        } else {
            violation(
                target.source,
                target.link,
                "markdown.link.destination",
                "local link `${target.destination}` resolves to missing path `${target.path}`",
                "restore the target or update the link to an existing repository path",
            )
        }
    }

    private fun invalidAnchor(
        repository: Repository,
        target: LinkTarget,
        rawFragment: String,
    ): Violation? {
        val document = repository.document(target.path)
        val fragment = decode(rawFragment)
        return if (document != null && document.headings.none { it.anchor == fragment }) {
            violation(
                target.source,
                target.link,
                "markdown.link.anchor",
                "local link `${target.destination}` names no rendered heading anchor `$fragment` in `${target.path}`",
                "use one of the target document's generated GitHub-style heading anchors",
            )
        } else {
            null
        }
    }

    private fun resolve(
        sourcePath: String,
        rawPath: String,
    ): String? {
        val decoded = decode(rawPath)
        val absoluteSyntax =
            decoded.startsWith('/') ||
                decoded.startsWith('\\') ||
                WINDOWS_DRIVE.matches(decoded)
        val parent = Path.of(sourcePath).parent ?: Path.of("")
        val resolved = parent.resolve(decoded.replace('/', java.io.File.separatorChar)).normalize()
        return resolved
            .takeUnless { absoluteSyntax || it.isAbsolute || it.startsWith("..") }
            ?.joinToString("/") { it.toString() }
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun violation(
        source: MarkdownDocument,
        link: MarkdownLink,
        rule: String,
        message: String,
        correction: String,
    ) = Violation(source.relativePath, link.line, rule, message, correction, POLICY)

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
}

private data class LinkTarget(
    val source: MarkdownDocument,
    val link: MarkdownLink,
    val destination: String,
    val path: String,
)
