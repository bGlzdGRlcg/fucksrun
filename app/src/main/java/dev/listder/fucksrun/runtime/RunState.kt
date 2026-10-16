package dev.listder.fucksrun.runtime

import dev.listder.fucksrun.protocol.LatLon
import dev.listder.fucksrun.protocol.RunContext
import dev.listder.fucksrun.protocol.RunTask

/** NEEDS_REVIEW is deliberately not resumable: a write may already have reached the server. */
enum class RunPhase { IDLE, STARTING, PLANNING, UPLOADING, PAUSED, NEEDS_REVIEW, COMPLETED, FAILED }

data class RunConfig(
    val token: String,
    val targetKm: Double = 3.0,
    val customRoute: List<LatLon> = emptyList(),
)

data class RunUiState(
    val taskId: String? = null,
    val phase: RunPhase = RunPhase.IDLE,
    val message: String = "尚未开始",
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val trackPoints: Int = 0,
    val nextScheduledMs: Long? = null,
    val stopRequested: Boolean = false,
    val pauseRequested: Boolean = false,
    val error: String? = null,
    val studentName: String = "",
    val studentNumber: String = "",
    val recordId: String? = null,
    val lastChunkSeq: Int = 0,
    val lastChunkHash: String = "",
    val writeInFlight: Boolean = false,
    val targetKm: Double? = null,
) {
    val isActive: Boolean get() = phase == RunPhase.STARTING || phase == RunPhase.PLANNING || phase == RunPhase.UPLOADING
    val canResume: Boolean get() = phase == RunPhase.PAUSED && !writeInFlight
}

internal data class RunSnapshot(
    val taskId: String,
    val config: RunConfig?,
    val phase: RunPhase,
    val context: RunContext? = null,
    val tasks: List<RunTask> = emptyList(),
    val nextTaskIndex: Int = 0,
    val trackPoints: Int = 0,
    val lastChunkSeq: Int = 0,
    val lastChunkHash: String = "",
    val inFlight: String? = null,
    val pauseRequested: Boolean = false,
    val stopRequested: Boolean = false,
    val studentName: String = "",
    val studentNumber: String = "",
    val message: String = "",
    val error: String? = null,
) {
    fun uiState() = RunUiState(
        taskId = taskId, phase = phase, message = message,
        completedChunks = lastChunkSeq, totalChunks = tasks.count { it.path == "/upload_running_chunk" },
        trackPoints = trackPoints, nextScheduledMs = tasks.getOrNull(nextTaskIndex)?.scheduledMs,
        stopRequested = stopRequested, pauseRequested = pauseRequested, error = error,
        studentName = studentName, studentNumber = studentNumber, recordId = context?.recordId?.toString(),
        lastChunkSeq = lastChunkSeq, lastChunkHash = lastChunkHash, writeInFlight = inFlight != null,
        targetKm = config?.targetKm,
    )
}
