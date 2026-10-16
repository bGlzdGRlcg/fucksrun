package dev.listder.fucksrun.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.listder.fucksrun.RouteEditorActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.listder.fucksrun.protocol.LatLon
import dev.listder.fucksrun.protocol.RunningRecordDetail
import dev.listder.fucksrun.protocol.RunningRecordSummary
import dev.listder.fucksrun.protocol.TrackSegment
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RecordsPanel(app: AppUiState, vm: AppViewModel, state: RecordsUiState, canDelete: Boolean = true) {
    var deleteTarget by remember { mutableStateOf<RunningRecordSummary?>(null) }
    val context = LocalContext.current
    var openedRecord by rememberSaveable { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.selected?.record?.recordId) {
        val detail = state.selected
        if (detail == null) {
            openedRecord = null
            mapError = null
        } else if (openedRecord != detail.record.recordId) {
            openedRecord = detail.record.recordId
            try {
                val points = detail.tracks.toTrackSegments().flatMap { it.points }
                require(points.size >= 2) { "此记录没有可显示的轨迹" }
                context.startActivity(RouteEditorActivity.createPreviewIntent(context, points, "记录轨迹"))
            } catch (error: Exception) {
                mapError = error.message ?: "无法打开轨迹"
            }
        }
    }
    LaunchedEffect(app.user) { if (app.user != null) vm.loadRecords() }
    if (app.user == null) {
        MessageCard("请先验证登录身份，再读取平台跑步记录。已有本机任务仍可在跑步页查看。")
        return
    }
    val canMutate = canDelete && !state.loading && !app.busy
    if (!canDelete) MessageCard("当前任务未结束，仍可查看记录；为避免与任务写入冲突，删除操作暂不可用。")

    state.error?.let { MessageCard(it, error = true) }
    mapError?.let { MessageCard(it, error = true) }
    state.notice?.let { MessageCard(it) }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

    state.selected?.let { detail ->
        BackHandler(onBack = vm::closeRecord)
        RecordDetailCard(detail, onBack = vm::closeRecord, onDelete = { deleteTarget = detail.record }, enabled = canMutate)
    } ?: run {
        SectionCard("跑步记录", MiuixIcons.ListView) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("普通跑步", MiuixIcons.Stopwatch, { vm.setRecordType("normal") },
                    enabled = !state.loading, primary = state.recordType == "normal", modifier = Modifier.weight(1f))
                ActionButton("补跑", MiuixIcons.Notes, { vm.setRecordType("makeup") },
                    enabled = !state.loading, primary = state.recordType == "makeup", modifier = Modifier.weight(1f))
            }
            ActionButton("刷新", MiuixIcons.Refresh, vm::loadRecords,
                enabled = !state.loading, modifier = Modifier.fillMaxWidth())
        }
        state.records.forEach { record ->
            RecordSummaryCard(record, onOpen = { vm.openRecord(record.recordId) },
                onDelete = { deleteTarget = record }, canOpen = !state.loading, canDelete = canMutate)
        }
        if (state.records.isNotEmpty() && state.hasMore) {
            ActionButton(if (state.loading) "读取中…" else "加载更多", MiuixIcons.ListView,
                vm::loadMoreRecords, enabled = !state.loading, modifier = Modifier.fillMaxWidth())
        }
    }

    deleteTarget?.let { record ->
        ConfirmationDialog(
            show = true,
            title = "删除 / 作废这条记录？",
            confirmText = "作废",
            onConfirm = { deleteTarget = null; vm.deleteRecord(record) },
            onDismiss = { deleteTarget = null },
            enabled = canMutate,
        )
    }
}

@Composable
private fun RecordSummaryCard(record: RunningRecordSummary, onOpen: () -> Unit, onDelete: () -> Unit, canOpen: Boolean, canDelete: Boolean) {
    SectionCard("${record.startTime ?: "跑步记录"} · ${record.recordId}", MiuixIcons.Stopwatch) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Metric(record.distance?.let { "%.3f km".format(it) } ?: "—", "距离")
            Metric(record.duration?.let(::formatDuration) ?: "—", "时长")
            Metric(record.pace ?: "—", "配速")
        }
        SupportingText(record.reachedCheckpoints?.let { "打卡点 $it/${record.totalCheckpoints ?: "?"}" } ?: "平台未返回打卡点进度")
        SupportingText(completionLabel(record.isCompleted))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("查看详情", MiuixIcons.Location, onOpen, enabled = canOpen, modifier = Modifier.weight(1f), primary = true)
            ActionButton("删除", MiuixIcons.Close, onDelete, enabled = canDelete, modifier = Modifier.weight(1f), destructive = true)
        }
    }
}

@Composable
private fun RecordDetailCard(detail: RunningRecordDetail, onBack: () -> Unit, onDelete: () -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard("记录详情 · ${detail.record.recordId}", MiuixIcons.Location) {
            ActionButton("返回", MiuixIcons.Back, onBack, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric(detail.record.distance?.let { "%.3f km".format(it) } ?: "—", "距离")
                Metric(detail.record.duration?.let(::formatDuration) ?: "—", "时长")
                Metric(detail.record.cadence?.toString() ?: "—", "步频")
            }
            SupportingText("${detail.record.startTime ?: "未知开始时间"} → ${detail.record.endTime ?: "未知结束时间"}")
            SupportingText(completionLabel(detail.record.isCompleted))
            SupportingText("配速 ${detail.record.pace ?: "—"} · 打卡点 ${detail.record.reachedCheckpoints ?: "?"}/${detail.record.totalCheckpoints ?: "?"}")
            detail.record.hasCheating?.let { SupportingText(if (it) "平台标记：存在异常" else "平台标记：未报告异常") }
            ActionButton("作废", MiuixIcons.Close, onDelete, enabled = enabled, modifier = Modifier.fillMaxWidth(), destructive = true)
        }
    }
}

@Composable
private fun RowScope.Metric(value: String, label: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.primary)
        SupportingText(label)
    }
}

private fun List<dev.listder.fucksrun.protocol.RunningRecordTrackSegment>.toTrackSegments(): List<TrackSegment> = mapNotNull { segment ->
    val points = segment.points.mapNotNull { point ->
        val latitude = (point["latitude"] as? Number)?.toDouble() ?: point["latitude"].toString().toDoubleOrNull()
        val longitude = (point["longitude"] as? Number)?.toDouble() ?: point["longitude"].toString().toDoubleOrNull()
        if (latitude != null && longitude != null) LatLon(latitude, longitude) else null
    }.filter { it.isValid() }
    if (points.isEmpty()) null else TrackSegment(points, segment.color ?: "#00FF00DD", segment.width?.toInt() ?: 6, segment.arrowLine != false)
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = safe % 3600 / 60
    val remainder = safe % 60
    return if (hours > 0) "%d小时%02d分%02d秒".format(hours, minutes, remainder)
    else "%d分%02d秒".format(minutes, remainder)
}

private fun completionLabel(completed: Boolean?): String = when (completed) {
    true -> "平台状态 · 已完成"
    false -> "平台状态 · 未完成"
    null -> "平台未返回完成状态"
}
