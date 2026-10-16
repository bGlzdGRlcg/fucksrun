package dev.listder.fucksrun.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.listder.fucksrun.protocol.ApiClient
import dev.listder.fucksrun.protocol.UserInfo
import dev.listder.fucksrun.runtime.RunConfig
import dev.listder.fucksrun.runtime.RunPhase
import dev.listder.fucksrun.runtime.RunRuntime
import dev.listder.fucksrun.runtime.RunUiState
import dev.listder.fucksrun.storage.LoginSettings
import dev.listder.fucksrun.storage.LoginSettingsStore
import dev.listder.fucksrun.storage.RouteSettings
import dev.listder.fucksrun.protocol.CurrentUserRanking
import dev.listder.fucksrun.protocol.RankingEntry
import dev.listder.fucksrun.protocol.RunningRecordDetail
import dev.listder.fucksrun.protocol.RunningRecordSummary
import dev.listder.fucksrun.protocol.TrackSegment
import dev.listder.fucksrun.RouteEditorActivity
import dev.listder.fucksrun.protocol.DrawnRoute
import dev.listder.fucksrun.protocol.TrackPlanner
import dev.listder.fucksrun.storage.RouteSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

data class RunEvent(val timestamp: Long, val message: String)

data class AppUiState(
    val settings: LoginSettings = LoginSettings(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val starting: Boolean = false,
    val user: UserInfo? = null,
    val error: String? = null,
    val notice: String? = null,
    val events: List<RunEvent> = emptyList(),
    val routeSummary: String = "尚未配置跑道，请先绘制线路",
)

data class RecordsUiState(
    val loading: Boolean = false,
    val records: List<RunningRecordSummary> = emptyList(),
    val selected: RunningRecordDetail? = null,
    val error: String? = null,
    val notice: String? = null,
    val nextPage: Int = 1,
    val hasMore: Boolean = true,
    val recordType: String = "normal",
)

data class MapSettingsUiState(
    val settings: RouteSettings = RouteSettings(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
)
data class RankingUiState(
    val loading: Boolean = false,
    val entries: List<RankingEntry> = emptyList(),
    val currentUser: CurrentUserRanking? = null,
    val error: String? = null,
)

/** Credentials live in memory, not in a saved-instance-state bundle. Runs belong to RunRuntime. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LoginSettingsStore(application)
    private val runtime = RunRuntime.get(application)
    val runState = runtime.state
    private val mutableState = MutableStateFlow(AppUiState())
    val state = mutableState.asStateFlow()
    private val mutableRecords = MutableStateFlow(RecordsUiState())
    val recordsState = mutableRecords.asStateFlow()
    private var notificationStopTask: String? = null
    private val routeStore = RouteSettingsStore(application)
    private var plannedSegments: List<TrackSegment> = emptyList()
    private var plannedDistance: Double? = null
    private var plannedDraft: String? = null
    private val mutableMapSettings = MutableStateFlow(MapSettingsUiState())
    private val mutableRanking = MutableStateFlow(RankingUiState())
    val rankingState = mutableRanking.asStateFlow()
    private var rankingJob: Job? = null
    val mapSettings = mutableMapSettings.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.load() }
                mutableState.update { it.copy(settings = saved) }
                routeChanged()
                if (normalizeToken(saved.token).isNotBlank()) authenticate(saved)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "无法读取登录设置，请检查设备存储后重试。") }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
        viewModelScope.launch {
            var previousTask: String? = null
            var previousMessage: String? = null
            runState.collect { run ->
                if (run.taskId != null && (run.taskId != previousTask || run.message != previousMessage)) {
                    val newTask = run.taskId != previousTask
                    mutableState.update { app ->
                        app.copy(events = ((if (newTask) emptyList() else app.events) +
                            RunEvent(System.currentTimeMillis(), run.message)).takeLast(50))
                    }
                    previousTask = run.taskId
                    previousMessage = run.message
                }
                if (notificationStopTask == run.taskId && run.taskId != null) {
                    notificationStopTask = null
                    if (run.isActive || run.phase == RunPhase.PAUSED) runtime.requestStop()
                }
            }
        }
    }

    private fun locked(run: RunUiState = runState.value): Boolean =
        run.isActive || run.phase == RunPhase.PAUSED || run.phase == RunPhase.NEEDS_REVIEW || run.writeInFlight

    private fun editable(): Boolean = !state.value.loading && !state.value.busy && !state.value.starting && !mutableRecords.value.loading && !locked()

    fun updateToken(value: String) {
        if (!editable()) return
        mutableState.update { it.copy(settings = it.settings.copy(token = value), user = null, error = null, notice = null) }
        mutableRecords.value = RecordsUiState()
        rankingJob?.cancel()
        mutableRanking.value = RankingUiState()
    }

    fun updateTarget(value: String) {
        if (!editable()) return
        mutableState.update { it.copy(settings = it.settings.copy(targetKm = value), error = null, notice = null) }
        plannedSegments = emptyList()
    }


    fun routeChanged() {
        viewModelScope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { routeStore.load() }
                mutableMapSettings.value = MapSettingsUiState(settings, loading = false)
                plannedSegments = emptyList()
                mutableState.update { it.copy(routeSummary = if (settings.draftJson.isNullOrBlank()) "尚未配置跑道，请先绘制线路" else "已保存跑道 / 绘制线路") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableMapSettings.update { it.copy(loading = false, error = error.message) }
                mutableState.update { it.copy(error = "读取地图设置失败，请检查设备存储后重试。") }
            }
        }
    }

    fun saveMapSettings(mapKey: String, securityCode: String, onSaved: () -> Unit = {}) {
        if (!editable()) return
        val updated = mutableMapSettings.value.settings.copy(mapKey = mapKey.trim(), securityCode = securityCode.trim())
        if (updated.mapKey.isBlank() != updated.securityCode.isBlank()) {
            mutableMapSettings.update { it.copy(error = "Key 和 securityJsCode 必须同时填写，或同时清空。") }
            return
        }
        mutableMapSettings.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { routeStore.save(updated) }
                mutableMapSettings.value = MapSettingsUiState(updated, loading = false)
                onSaved()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableMapSettings.update { it.copy(saving = false, error = error.message ?: "地图设置保存失败") }
            }
        }
    }

    fun clearDrawnRoute() {
        if (!editable()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val settings = routeStore.load()
                    routeStore.save(settings.copy(draftJson = null))
                }
                plannedSegments = emptyList()
                mutableState.update { it.copy(routeSummary = "尚未配置跑道，请先绘制线路", notice = "已清除线路，请重新使用跑道模式配置。") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "清除线路失败：${safeMessage(error, it.settings)}") }
            }
        }
    }

    fun openRouteEditorIntent(context: Context, mode: String = "track"): Intent = RouteEditorActivity.createEditorIntent(context, mode)

    /** Builds the exact persisted route used by start(), then opens the AMap viewer. */
    fun prepareMapPreview(context: Context, targetKm: Double, title: String, callback: (Intent?) -> Unit) {
        if (state.value.busy || state.value.loading || state.value.starting) return
        if (!targetKm.isFinite() || targetKm !in 0.1..50.0) {
            mutableState.update { it.copy(error = "目标距离须为 0.1–50 公里之间的有限数字。") }
            callback(null)
            return
        }
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val points = preparedRoute(targetKm).flatMap { it.points }
                callback(RouteEditorActivity.createPreviewIntent(context, points, title))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "生成轨迹失败：${safeMessage(error, it.settings)}") }
                callback(null)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Returns the saved drawing's traversed length in kilometres. */
    fun drawnRouteLengthKm(): Double? {
        val draft = runCatching { routeStore.load().draftJson?.takeIf { it.isNotBlank() } }.getOrNull() ?: return null
        return runCatching {
            val path = DrawnRoute.parse(draft).path
            require(path.size >= 2)
            path.zipWithNext().sumOf { (a, b) -> TrackPlanner.distance(a, b) } / 1000.0
        }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun prepareCurrentMapPreview(context: Context, title: String, callback: (Intent?) -> Unit) {
        val points = runtime.previewTrack().flatMap { it.points }
        if (points.isEmpty()) {
            mutableState.update { it.copy(error = "本次任务尚未生成轨迹。") }
            callback(null)
        } else callback(RouteEditorActivity.createPreviewIntent(context, points, title))
    }

    fun setTargetDistance(target: Double) {
        if (!target.isFinite() || target !in 0.1..50.0) {
            mutableState.update { it.copy(error = "目标距离须为 0.1–50 公里之间的有限数字。") }
            return
        }
        val value = target.toString()
        mutableState.update { it.copy(settings = it.settings.copy(targetKm = value), error = null, notice = null) }
        plannedSegments = emptyList()
        plannedDistance = null
        plannedDraft = null
    }

    private suspend fun preparedRoute(target: Double): List<TrackSegment> = withContext(Dispatchers.IO) {
        val draft = routeStore.load().draftJson
        require(!draft.isNullOrBlank()) { "请先打开跑道模式，选择两个弯道端点和一侧直道，再保存线路。" }
        if (plannedSegments.isEmpty() || plannedDistance != target || plannedDraft != draft) {
            plannedSegments = listOf(TrackSegment(DrawnRoute.parse(draft).generate(target)))
            plannedDistance = target
            plannedDraft = draft
        }
        plannedSegments
    }


    fun login() {
        if (!editable()) return
        viewModelScope.launch { authenticate(state.value.settings) }
    }

    private suspend fun authenticate(saved: LoginSettings) {
        val settings = saved.copy(token = normalizeToken(saved.token))
        val problem = when {
            settings.token.isBlank() -> "请输入 Token。"
            settings.token.any { it == '\r' || it == '\n' } -> "Token 不能包含换行。"
            else -> null
        }
        if (problem != null) {
            mutableState.update { it.copy(error = problem) }
            return
        }
        mutableState.update { it.copy(busy = true, user = null, error = null, notice = null, settings = settings) }
        try {
            try {
                withContext(Dispatchers.IO) { store.save(settings) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "登录设置保存失败，未发送验证请求。请检查设备存储后重试。") }
                return
            }
            val user = withContext(Dispatchers.IO) { ApiClient().getUserInfo(settings.token) }
            require(user.studentNumber.isNotBlank()) { "账号尚未绑定学生身份。" }
            mutableState.update { it.copy(user = user, notice = null) }
            refreshRanking()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(error = "验证失败：${safeMessage(error, settings)}") }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun start() {
        if (!editable() || state.value.user == null) return
        val settings = state.value.settings
        val target = settings.targetKm.toDoubleOrNull()
        if (target == null || !target.isFinite() || target !in 0.1..50.0) {
            mutableState.update { it.copy(error = "目标距离须为 0.1–50 公里之间的有限数字。") }
            return
        }
        mutableState.update { it.copy(starting = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(settings) }
                val segments = preparedRoute(target)
                runtime.start(RunConfig(token = normalizeToken(settings.token), targetKm = target,
                    customRoute = segments.flatMap { it.points })).join()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "启动失败：${safeMessage(error, settings)}") }
            } finally {
                mutableState.update { it.copy(starting = false) }
            }
        }
    }

    fun resume() {
        val run = runState.value
        if (run.canResume && !run.stopRequested) runtime.resume()
    }

    fun pause() { if (runState.value.isActive) runtime.pause() }
    fun requestStop() {
        val run = runState.value
        if (run.isActive || run.phase == RunPhase.PAUSED) runtime.requestStop()
    }
    fun dismissStop() { runtime.dismissStopRequest() }
    fun confirmStop(taskId: String?) {
        val run = runState.value
        if (taskId != null && taskId == run.taskId && run.phase == RunPhase.PAUSED && !run.writeInFlight && run.stopRequested) {
            runtime.confirmStop()
        }
    }
    fun clearLocal(taskId: String?) {
        val run = runState.value
        if (state.value.busy || taskId == null || run.taskId != taskId ||
            run.phase !in setOf(RunPhase.NEEDS_REVIEW, RunPhase.COMPLETED, RunPhase.FAILED)) return
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                runtime.clearState().join()
                if (runState.value.taskId == taskId) {
                    mutableState.update { it.copy(error = "本地状态未能清除，原任务仍保留。请检查设备存储后重试。") }
                } else {
                    mutableState.update { it.copy(notice = "本地任务状态已清除") }
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
    fun loadRecords() { fetchRecords(reset = true) }

    fun refreshRanking() {
        val token = normalizeToken(state.value.settings.token)
        if (token.isBlank() || state.value.user == null) {
            mutableRanking.update { it.copy(loading = false, entries = emptyList(), currentUser = null, error = null) }
            return
        }
        rankingJob?.cancel()
        mutableRanking.update { it.copy(loading = true, error = null) }
        rankingJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { ApiClient().getAllClassRanking(token, pageSize = RANKING_PAGE_SIZE) }
                mutableRanking.update { it.copy(loading = false, entries = page.entries, currentUser = page.currentUser, error = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRanking.update { it.copy(loading = false, error = safeMessage(error, state.value.settings)) }
            }
        }
    }

    fun loadMoreRecords() { fetchRecords(reset = false) }

    fun setRecordType(type: String) {
        if (type !in listOf("normal", "makeup") || mutableRecords.value.loading) return
        mutableRecords.value = RecordsUiState(recordType = type)
        loadRecords()
    }

    private fun fetchRecords(reset: Boolean) {
        val token = normalizeToken(state.value.settings.token)
        val before = mutableRecords.value
        if (token.isBlank() || before.loading || (!reset && !before.hasMore)) return
        val page = if (reset) 1 else before.nextPage
        mutableRecords.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val records = withContext(Dispatchers.IO) { ApiClient().getRunningRecords(token, page, 20, before.recordType) }
                mutableRecords.update { current -> current.copy(loading = false,
                    records = if (reset) records else (current.records + records).distinctBy { it.recordId },
                    nextPage = page + 1, hasMore = records.size == 20, error = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRecords.update { it.copy(loading = false, error = safeMessage(error, state.value.settings)) }
            }
        }
    }

    fun openRecord(recordId: String) {
        val token = normalizeToken(state.value.settings.token)
        if (token.isBlank() || mutableRecords.value.loading) return
        mutableRecords.update { it.copy(loading = true, error = null, selected = null) }
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { ApiClient().getRunningRecordDetail(token, recordId) }
                mutableRecords.update { it.copy(loading = false, selected = detail) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRecords.update { it.copy(loading = false, error = safeMessage(error, state.value.settings)) }
            }
        }
    }

    fun closeRecord() { mutableRecords.update { it.copy(selected = null) } }

    fun deleteRecord(record: RunningRecordSummary) {
        val token = normalizeToken(state.value.settings.token)
        if (token.isBlank() || mutableRecords.value.loading) return
        if (runState.value.recordId == record.recordId && locked()) {
            mutableRecords.update { it.copy(error = "当前任务正在使用此记录，请通过跑步页停止并确认作废。") }
            return
        }
        mutableRecords.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient().deleteRunningRecord(token, record.recordId, record.runSessionId) }
                mutableRecords.update { current -> current.copy(
                    loading = false,
                    records = current.records.filterNot { item -> item.recordId == record.recordId },
                    selected = null,
                    notice = "记录已请求作废",
                ) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRecords.update { it.copy(loading = false, error = safeMessage(error, state.value.settings)) }
            }
        }
    }

    fun notificationStop(taskId: String) {
        notificationStopTask = taskId
        val run = runState.value
        if (run.taskId == taskId) {
            notificationStopTask = null
            if (run.isActive || run.phase == RunPhase.PAUSED) runtime.requestStop()
        }
    }
    fun notificationDenied() {
        mutableState.update { it.copy(notice = "通知权限未开启，任务仍可启动。请在应用内查看状态，或前往系统设置开启通知。") }
    }
    fun logout() {
        if (!editable()) return
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.clearToken() }
                mutableState.update { it.copy(user = null, settings = it.settings.copy(token = "")) }
                mutableRecords.value = RecordsUiState()
                rankingJob?.cancel()
                mutableRanking.value = RankingUiState()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "无法清除已保存的 Token，未退出登录。请重试。") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun normalizeToken(value: String): String = value.trim().let {
        if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it
    }
    private fun safeMessage(error: Exception, settings: LoginSettings): String {
        var message = error.message ?: "网络或服务异常，请稍后重试。"
        listOf(settings.token, normalizeToken(settings.token)).filter { it.isNotEmpty() }.forEach {
            message = message.replace(it, "[已隐藏]")
        }
        return message.take(500)
    }
    private companion object {
        const val RANKING_PAGE_SIZE = 100
    }
}
