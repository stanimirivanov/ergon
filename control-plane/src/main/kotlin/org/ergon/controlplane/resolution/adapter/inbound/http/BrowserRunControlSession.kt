package org.ergon.controlplane.resolution.adapter.inbound.http

import jakarta.servlet.http.HttpSession
import org.ergon.controlplane.resolution.application.RunCommandControl
import org.ergon.controlplane.resolution.application.RunControlFence
import org.springframework.web.util.WebUtils
import java.util.UUID

private const val CONTROL_HOLDER_ATTRIBUTE = "org.ergon.browser.run-control-holder"

/** Generate once per server session; never persist the session ID or accept a binding in JSON. */
internal fun HttpSession.runControlHolder(): UUID =
    synchronized(WebUtils.getSessionMutex(this)) {
        (getAttribute(CONTROL_HOLDER_ATTRIBUTE) as? UUID) ?: UUID.randomUUID().also {
            setAttribute(CONTROL_HOLDER_ATTRIBUTE, it)
        }
    }

/** Historical receipts compare the public fence only; fresh admission additionally checks this private binding. */
internal fun RunControlFence?.forSession(session: HttpSession): RunCommandControl? =
    this?.let { RunCommandControl(it, session.runControlHolder()) }
