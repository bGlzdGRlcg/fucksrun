package dev.listder.fucksrun.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.listder.fucksrun.protocol.ApiClient
import dev.listder.fucksrun.protocol.ChunkTask
import dev.listder.fucksrun.protocol.FinishTask
import dev.listder.fucksrun.protocol.JsonValues
import dev.listder.fucksrun.protocol.LatLon
import dev.listder.fucksrun.protocol.TrackSegment
import dev.listder.fucksrun.protocol.TaskBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Process-owned worker. An Activity only observes it; UI disposal never cancels a run. */
class RunRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = RunSessionStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private val ready = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(RunUiState(message = "正在读取本地状态"))
    val state: StateFlow<RunUiState> = mutableState.asStateFlow()
    private var snapshot: RunSnapshot? = null
    private var worker: Job? = null
    private var discardPending = false
    private var serviceGeneration = 0L

    init {
        scope.launch {
            synchronized(monitor) {
                try {
                    val saved = store.load()
                    snapshot = saved?.let {
                        when {
                            it.inFlight != null -> it.copy(phase = RunPhase.NEEDS_REVIEW,
                                pauseRequested = true, message = "上次写入结果未知，请到平台核对；不会自动重试")
                            it.phase in ACTIVE_PHASES -> it.copy(phase = RunPhase.PAUSED,
                                pauseRequested = true, message = "进程已重启，任务已暂停；没有自动发送请求")
                            else -> it
                        }
                    }
                    if (snapshot != null && snapshot != saved) store.commit(snapshot!!)
                    mutableState.value = snapshot?.uiState() ?: RunUiState()
                } catch (_: Exception) {
                    // Keep the original file intact for investigation until the user explicitly clears it.
                    snapshot = RunSnapshot(UUID.randomUUID().toString(), null, RunPhase.NEEDS_REVIEW,
                        message = "无法读取已保存的任务，请先核对平台记录，再清除本地状态",
                        error = "本地任务恢复失败；未发送任何请求")
                    mutableState.value = snapshot!!.uiState()
                } finally {
                    ready.complete(Unit)
                }
            }
        }
    }

    /** Preview the exact rounded upload points, including recovered sessions; never regenerate a route. */
    fun previewTrack(): List<TrackSegment> {
        val tasks = current()?.tasks ?: return emptyList()
        return tasks.mapNotNull { task ->
            if (task !is ChunkTask) return@mapNotNull null
            val points = (task.payload["points"] as? List<*>).orEmpty().mapNotNull coordinate@{ value ->
                val point = value as? Map<*, *> ?: return@coordinate null
                val latitude = JsonValues.double(point["latitude"]) ?: return@coordinate null
                val longitude = JsonValues.double(point["longitude"]) ?: return@coordinate null
                LatLon(latitude, longitude).takeIf { it.isValid() }
            }
            if (points.isEmpty()) null else TrackSegment(points)
        }
    }

    fun start(config: RunConfig) = command {
        synchronized(monitor) {
            if (worker != null || snapshot?.phase !in setOf(null, RunPhase.IDLE, RunPhase.COMPLETED, RunPhase.FAILED)) return@command
            val id = UUID.randomUUID().toString()
            val validation = validate(config)
            val fresh = RunSnapshot(id, if (validation == null) config.copy(
                token = config.token.trim().removePrefix("Bearer ").trim(),
                customRoute = config.customRoute.toList(),
            ) else null,
                if (validation == null) RunPhase.STARTING else RunPhase.FAILED,
                message = validation ?: "正在启动前台运行服务", error = validation)
            try {
                store.commit(fresh)
                snapshot = fresh
                mutableState.value = fresh.uiState()
            } catch (_: Exception) {
                mutableState.value = RunUiState(phase = RunPhase.FAILED, message = "无法保存任务，未发送请求", error = "本地存储写入失败")
                return@command
            }
            if (validation != null) return@command
            discardPending = false
            launchService(id)
        }
    }

    fun pause() = command {
        val value = current() ?: return@command
        if (value.phase !in ACTIVE_PHASES) return@command
        change(value.taskId) { it.copy(pauseRequested = true,
            message = if (it.inFlight != null) "等待当前请求结果后暂停" else "正在暂停") }
    }

    fun resume() = command {
        val value = current() ?: return@command
        if (value.phase != RunPhase.PAUSED || value.inFlight != null || value.config == null || value.stopRequested) return@command
        if (synchronized(monitor) { worker != null }) return@command
        change(value.taskId) { it.copy(phase = RunPhase.STARTING, pauseRequested = false,
            error = null, message = "正在恢复前台运行服务") }
        launchService(value.taskId)
    }

    /** This stops future requests, not an in-flight HTTP call. The UI must ask for confirmation. */
    fun requestStop() = command {
        val value = current() ?: return@command
        if (value.phase in setOf(RunPhase.IDLE, RunPhase.COMPLETED, RunPhase.FAILED)) return@command
        change(value.taskId) { it.copy(stopRequested = true, pauseRequested = true,
            message = if (it.inFlight != null) "等待当前请求结果，再确认停止" else "请确认是否停止并作废记录") }
    }

    fun dismissStopRequest() = command {
        val value = current() ?: return@command
        change(value.taskId) { it.copy(stopRequested = false,
            message = if (it.phase == RunPhase.PAUSED) "任务已暂停，可手动恢复" else it.message) }
    }

    /** Explicit confirmation is the only path to the documented discard endpoint. */
    fun confirmStop() = command {
        val value = current() ?: return@command
        if (!value.stopRequested || value.phase == RunPhase.NEEDS_REVIEW || value.inFlight != null) return@command
        if (synchronized(monitor) { worker != null }) return@command
        if (value.context == null) {
            change(value.taskId) { it.copy(phase = RunPhase.FAILED, stopRequested = false,
                message = "已停止；尚未创建服务端记录") }
        } else {
            synchronized(monitor) { discardPending = true }
            change(value.taskId) { it.copy(phase = RunPhase.STARTING, message = "正在启动服务以作废记录") }
            launchService(value.taskId)
        }
    }

    /** Caller owns the confirmation dialog, particularly when abandoning NEEDS_REVIEW evidence. */
    fun clearState() = command {
        synchronized(monitor) {
            val value = snapshot
            if (worker != null || value?.phase in ACTIVE_PHASES || value?.phase == RunPhase.PAUSED) return@command
            store.clear()
            snapshot = null
            discardPending = false
            mutableState.value = RunUiState()
        }
    }

    /** Only called after Service.startForeground succeeds. Duplicate/stale intents cannot start work. */
    internal fun serviceReady(taskId: String, generation: Long) = command {
        synchronized(monitor) {
            val value = snapshot ?: return@command
            if (value.taskId != taskId || generation != serviceGeneration || value.phase !in ACTIVE_PHASES || worker != null) return@command
            val shouldDiscard = discardPending
            discardPending = false
            worker = scope.launch {
                try {
                    if (shouldDiscard) discard(taskId) else execute(taskId)
                } catch (failure: Exception) {
                    failed(taskId, failure)
                } finally {
                    synchronized(monitor) { if (snapshot?.taskId == taskId) worker = null }
                }
            }
        }
    }

    internal fun serviceLost(taskId: String, generation: Long? = null) = command {
        synchronized(monitor) {
            val value = snapshot ?: return@command
            if (value.taskId != taskId || (generation != null && generation != serviceGeneration) || value.phase !in ACTIVE_PHASES) return@command
            val phase = when {
                value.inFlight != null -> RunPhase.NEEDS_REVIEW
                value.context == null && worker == null -> RunPhase.FAILED
                else -> RunPhase.PAUSED
            }
            try {
                change(taskId) { it.copy(phase = phase, pauseRequested = true,
                    message = if (phase == RunPhase.NEEDS_REVIEW) "前台服务中断，写入结果待核对；不会重试" else "前台服务已停止，任务未继续发送") }
            } finally {
                worker?.cancel()
            }
        }
    }

    private suspend fun execute(taskId: String) {
        var value = current(taskId) ?: return
        val config = value.config ?: return
        val api = ApiClient()
        if (value.context == null && config.customRoute.size < 2) {
            change(taskId) { it.copy(phase = RunPhase.FAILED, message = "请先提供至少两个自定义线路点", error = "未创建服务端记录") }
            return
        }
        if (value.context == null) {
            if (pauseAtBoundary(taskId)) return
            val user = api.getUserInfo(config.token)
            currentCoroutineContext().ensureActive()
            if (user.studentNumber.isBlank()) {
                change(taskId) { it.copy(phase = RunPhase.FAILED, message = "当前账号未绑定学生身份", error = "不能创建跑步记录") }
                return
            }
            change(taskId) { it.copy(studentName = user.name, studentNumber = user.studentNumber) }
            if (pauseAtBoundary(taskId)) return
            if (!boundary(taskId, "/create_running_record")) return
            val session = api.createRun(config.token, user.studentNumber)
            currentCoroutineContext().ensureActive()
            change(taskId) { it.copy(context = session, inFlight = null, phase = RunPhase.PLANNING, message = "记录已创建，正在规划轨迹") }
        }
        value = current(taskId) ?: return
        if (value.tasks.isEmpty()) {
            if (pauseAtBoundary(taskId)) return
            change(taskId) { it.copy(phase = RunPhase.PLANNING, message = "正在规划闭环轨迹") }
            val session = current(taskId)?.context ?: error("缺少已创建会话")
            // Keep server checkpoints unchanged so TaskBuilder reports missed points honestly.
            val track = config.customRoute
            currentCoroutineContext().ensureActive()
            val tasks = TaskBuilder.prepareTasks(session, track, config.targetKm)
            require(tasks.isNotEmpty() && tasks.last() is FinishTask) { "分片计划无效" }
            change(taskId) { it.copy(tasks = tasks, trackPoints = track.size,
                phase = RunPhase.UPLOADING, message = "轨迹已生成，按实际时间等待上传") }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (pauseAtBoundary(taskId)) return
            value = current(taskId) ?: return
            val task = value.tasks.getOrNull(value.nextTaskIndex) ?: return
            change(taskId) { it.copy(phase = RunPhase.UPLOADING,
                message = if (task is FinishTask) "等待最终提交时间" else "等待分片 ${value.nextTaskIndex + 1}/${value.tasks.size - 1}") }
            while (System.currentTimeMillis() < task.scheduledMs) {
                if (pauseAtBoundary(taskId)) return
                delay((task.scheduledMs - System.currentTimeMillis()).coerceIn(1L, 1000L))
            }
            if (pauseAtBoundary(taskId)) return
            if (!boundary(taskId, task.path)) return
            val response = api.submit(config.token, task).requireSuccess()
            currentCoroutineContext().ensureActive()
            change(taskId) {
                it.copy(nextTaskIndex = it.nextTaskIndex + 1, inFlight = null,
                    lastChunkSeq = if (task is ChunkTask) task.seq else it.lastChunkSeq,
                    lastChunkHash = if (task is ChunkTask) task.chunkHash else it.lastChunkHash,
                    phase = if (task is FinishTask) RunPhase.COMPLETED else RunPhase.UPLOADING,
                    pauseRequested = if (task is FinishTask) false else it.pauseRequested,
                    stopRequested = if (task is FinishTask) false else it.stopRequested,
                    message = if (task is FinishTask) response.msg.ifBlank { "跑步记录提交成功" } else "分片已确认，等待下一片")
            }
            if (task is FinishTask) return
        }
    }

    private suspend fun discard(taskId: String) {
        val value = current(taskId) ?: return
        val config = value.config ?: error("缺少任务配置")
        val session = value.context ?: error("缺少会话")
        if (!boundary(taskId, "/discard_running_record", allowStop = true)) return
        ApiClient().discardRun(config.token, session).requireSuccess()
        currentCoroutineContext().ensureActive()
        change(taskId) { it.copy(phase = RunPhase.FAILED, inFlight = null, stopRequested = false,
            pauseRequested = false, message = "已停止，服务端记录已作废", error = null) }
    }

    private fun boundary(taskId: String, path: String, allowStop: Boolean = false): Boolean = synchronized(monitor) {
        val value = snapshot?.takeIf { it.taskId == taskId } ?: return@synchronized false
        check(value.inFlight == null && value.phase in ACTIVE_PHASES) { "任务不允许发送请求" }
        if (!allowStop && (value.pauseRequested || value.stopRequested)) {
            change(taskId) { it.copy(phase = RunPhase.PAUSED,
                message = if (it.stopRequested) "已暂停，请确认停止并作废记录" else "任务已暂停，未继续发送请求") }
            return@synchronized false
        }
        change(taskId) { it.copy(inFlight = path, message = "正在提交 ${path.substringAfterLast('/')}" ) }
        true
    }

    private fun pauseAtBoundary(taskId: String): Boolean {
        val value = current(taskId) ?: return true
        if (value.phase !in ACTIVE_PHASES) return true
        if (!value.pauseRequested && !value.stopRequested) return false
        change(taskId) { it.copy(phase = RunPhase.PAUSED,
            message = if (it.stopRequested) "已暂停，请确认停止并作废记录" else "任务已暂停，未继续发送请求") }
        return true
    }

    private fun failed(taskId: String, cause: Exception? = null) {
        synchronized(monitor) {
            val value = snapshot?.takeIf { it.taskId == taskId } ?: return
            if (value.phase !in ACTIVE_PHASES) return
            val hasRemoteBoundary = value.inFlight != null || value.context != null
            val secrets = listOfNotNull(value.config?.token, value.context?.sessionSalt)
                .filter { it.isNotBlank() }
            val detail = secrets.fold(cause?.message.orEmpty()) { text, secret -> text.replace(secret, "[已隐藏]") }
                .take(300)
            val failed = value.copy(phase = RunPhase.NEEDS_REVIEW,
                pauseRequested = true,
                message = "运行异常，已停止；不会自动重试任何请求",
                error = detail.ifBlank { if (hasRemoteBoundary) "请核对平台记录，服务端记录可能未结束或写入结果未知" else "用户信息读取或本地持久化失败，尚未确认创建记录" })
            // If this save fails the previous durable in-flight marker remains, never a false success.
            try { store.commit(failed) } catch (_: Exception) { /* Keep the last durable boundary. */ }
            snapshot = failed
            mutableState.value = failed.uiState()
            worker?.cancel()
        }
    }

    private fun launchService(taskId: String) {
        val generation = synchronized(monitor) { ++serviceGeneration }
        try {
            ContextCompat.startForegroundService(app, Intent(app, RunForegroundService::class.java)
                .setAction(RunForegroundService.ACTION_START)
                .putExtra(RunForegroundService.EXTRA_TASK_ID, taskId)
                .putExtra(RunForegroundService.EXTRA_GENERATION, generation))
            scope.launch {
                delay(10_000)
                val value = current(taskId)
                if (value?.phase == RunPhase.STARTING && synchronized(monitor) { worker == null }) serviceLost(taskId, generation)
            }
        } catch (_: Exception) {
            serviceLost(taskId, generation)
        }
    }

    private fun current(taskId: String? = null): RunSnapshot? = synchronized(monitor) {
        snapshot?.takeIf { taskId == null || it.taskId == taskId }
    }

    private fun change(taskId: String, transform: (RunSnapshot) -> RunSnapshot) = synchronized(monitor) {
        val old = snapshot?.takeIf { it.taskId == taskId } ?: error("任务已更换")
        val next = transform(old)
        store.commit(next)
        snapshot = next
        mutableState.value = next.uiState()
    }

    private fun command(block: suspend () -> Unit): Job = scope.launch {
        ready.await()
        try { block() } catch (failure: Exception) { current()?.let { failed(it.taskId, failure) } }
    }

    private fun validate(config: RunConfig): String? {
        if (config.token.trim().removePrefix("Bearer ").isBlank()) return "请先输入 Token"
        if (!config.targetKm.isFinite() || config.targetKm !in 0.1..50.0) return "目标距离必须在 0.1–50 公里之间"
        if (config.customRoute.size < 2) return "请先提供至少两个自定义线路点"
        return null
    }

    companion object {
        private val ACTIVE_PHASES = setOf(RunPhase.STARTING, RunPhase.PLANNING, RunPhase.UPLOADING)
        @Volatile private var instance: RunRuntime? = null
        fun get(context: Context): RunRuntime = instance ?: synchronized(this) {
            instance ?: RunRuntime(context).also { instance = it }
        }
    }
}
