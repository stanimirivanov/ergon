package org.ergon.controlplane.followup.adapter.out.persistence

/**
 * Latest metadata for the caller's `item` alias, exposed as `latest_priority`.
 * The tenant/work PK supports one indexed latest-row lookup. Callers retain
 * authority/active-work predicates and page keys; this fragment grants no access.
 * Reading both columns from one row avoids torn value/revision pairs.
 */
internal val latestFollowUpPriorityJoin =
    """
    LEFT JOIN LATERAL (
        SELECT assignment.priority, assignment.priority_revision
        FROM human_follow_up_priority_assignments assignment
        WHERE assignment.tenant_id = item.tenant_id AND assignment.work_item_id = item.work_item_id
        ORDER BY assignment.priority_revision DESC
        LIMIT 1
    ) latest_priority ON TRUE
    """.trimIndent()
