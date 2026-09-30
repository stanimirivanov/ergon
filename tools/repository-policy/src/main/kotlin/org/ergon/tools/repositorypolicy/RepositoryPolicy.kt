package org.ergon.tools.repositorypolicy

import java.nio.file.Path

internal object RepositoryPolicy {
    fun check(root: Path): List<Violation> {
        val repository = Repository.load(root)
        return (
            LinkPolicy.check(repository) +
                TldrPolicy.check(repository) +
                AdrPolicy.check(repository) +
                MilestonePolicy.check(repository) +
                TemplatePolicy.check(repository)
        ).sorted()
    }
}
