package org.ergon.identity.domain

import java.util.UUID

/**
 * Identifies one tenant boundary across Ergon capabilities.
 *
 * Every tenant-owned query, write, uniqueness rule, and association must retain
 * this scope. Dropping it is a data leak, not a convenience.
 */
@JvmInline
value class TenantId(
    val value: UUID,
)
