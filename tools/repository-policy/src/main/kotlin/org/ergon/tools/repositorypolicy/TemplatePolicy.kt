package org.ergon.tools.repositorypolicy

internal const val ISSUE_TEMPLATE = ".github/ISSUE_TEMPLATE/implementation.md"
internal const val PULL_REQUEST_TEMPLATE = ".github/PULL_REQUEST_TEMPLATE.md"
internal const val CONTRIBUTING = "CONTRIBUTING.md"
internal const val ISSUE_POLICY = "CONTRIBUTING.md#required-issue-structure"
internal const val PULL_REQUEST_POLICY = "CONTRIBUTING.md#pull-request-description"

internal object TemplatePolicy {
    fun check(repository: Repository): List<Violation> {
        val issueViolations = IssueTemplatePolicy.check(repository)
        val pullRequestViolations = PullRequestTemplatePolicy.check(repository)
        return issueViolations + pullRequestViolations
    }
}
