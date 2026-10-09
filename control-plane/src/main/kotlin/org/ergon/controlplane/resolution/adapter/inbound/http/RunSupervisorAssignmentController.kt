package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.AssignRunSupervisorCommand
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentConflictException
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRecording
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.ergon.controlplane.resolution.application.RunSupervisorNotEligibleException
import org.ergon.controlplane.resolution.application.SupervisedRunNotFoundException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Machine-only command boundary for an immutable first run-supervisor assignment. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/supervisor-assignments")
class RunSupervisorAssignmentController(
    private val assignments: RunSupervisorAssignmentService,
) {
    /**
     * Records one assignee after the security chain authenticates the JWT's
     * issuer, tenant, dedicated audience, and assignment scope. A browser session or
     * ordinary resolver token cannot create or change supervision authority.
     */
    @PostMapping
    fun assign(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @RequestBody request: AssignRunSupervisorRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RunSupervisorAssignmentResponse> {
        val subject = jwt.subject ?: throw IllegalArgumentException("assignment token requires a subject")
        val recording =
            assignments.assign(
                AssignRunSupervisorCommand(
                    tenantId = tenantId,
                    runId = runId,
                    supervisorActorId = request.supervisorActorId,
                    commandId = request.commandId,
                    assigningMachineSubject = subject,
                ),
            )
        return ResponseEntity
            .status(if (recording.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(recording.toResponse())
    }
}

/** Exact machine-command identity and the nominated tenant resolver. */
data class AssignRunSupervisorRequest(
    val commandId: UUID,
    val supervisorActorId: UUID,
)

/** Durable assignment receipt without the private authority-evidence identity. */
data class RunSupervisorAssignmentResponse(
    val assignmentId: UUID,
    val runId: UUID,
    val supervisorActorId: UUID,
    val assignedAt: Instant,
    val recordedAt: Instant,
)

private fun RunSupervisorAssignmentRecording.toResponse(): RunSupervisorAssignmentResponse =
    RunSupervisorAssignmentResponse(
        assignmentId = stored.assignment.id,
        runId = stored.assignment.runId.value,
        supervisorActorId = stored.assignment.supervisorActorId.value,
        assignedAt = stored.assignment.assignedAt,
        recordedAt = stored.recordedAt,
    )

/** Stable machine-command problems without revealing an incumbent supervisor. */
@RestControllerAdvice(assignableTypes = [RunSupervisorAssignmentController::class])
class RunSupervisorAssignmentExceptionHandler {
    @ExceptionHandler(SupervisedRunNotFoundException::class)
    fun missing(exception: SupervisedRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "resolution-run-not-found", "Resolution run not found", exception)

    @ExceptionHandler(RunSupervisorNotEligibleException::class)
    fun ineligible(exception: RunSupervisorNotEligibleException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "run-supervisor-not-eligible", "Run supervisor not eligible", exception)

    @ExceptionHandler(RunSupervisorAssignmentConflictException::class)
    fun conflict(exception: RunSupervisorAssignmentConflictException): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "run-supervisor-assignment-conflict",
            "Run supervisor assignment conflicts",
            exception,
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(exception: IllegalArgumentException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "invalid-run-supervisor-assignment",
            "Invalid run supervisor assignment",
            exception,
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        exception: Exception,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
            this.title = title
        }
}
