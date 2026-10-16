package dev.listder.fucksrun.ui
import android.app.Activity
import android.content.pm.PackageManager
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.listder.fucksrun.runtime.RunPhase
import dev.listder.fucksrun.runtime.RunUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class RunFlowStage { IDLE, CHOOSE_MODE, TARGET, EDITING, PREVIEW, READY_TO_START }

@Composable
fun FucksrunApp(vm: AppViewModel) {
    val app by vm.state.collectAsState()
    val records by vm.recordsState.collectAsState()
    val run by vm.runState.collectAsState()
    val ranking by vm.rankingState.collectAsState()
    val mapSettings by vm.mapSettings.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var flowStage by rememberSaveable { mutableStateOf(RunFlowStage.IDLE) }
    var flowMode by rememberSaveable { mutableStateOf("track") }
    var pendingRouteIntent by remember { mutableStateOf<Intent?>(null) }
    var flowTarget by rememberSaveable { mutableStateOf(app.settings.targetKm) }
    var clearTask by rememberSaveable { mutableStateOf<String?>(null) }
    var logoutDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPermissionAction by rememberSaveable { mutableStateOf<String?>(null) }
    var mapSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val login: () -> Unit = { keyboard?.hide(); focus.clearFocus(); vm.login() }
    val routeEditor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (flowStage) {
            RunFlowStage.EDITING -> {
                if (result.resultCode != Activity.RESULT_OK) {
                    flowStage = RunFlowStage.IDLE
                } else {
                    vm.routeChanged()
                    val target = if (flowMode == "track") flowTarget.toDoubleOrNull() else vm.drawnRouteLengthKm()
                    if (target == null || !target.isFinite() || target !in 0.1..50.0) {
                        vm.setTargetDistance(target ?: Double.NaN)
                        flowStage = RunFlowStage.IDLE
                    } else {
                        vm.setTargetDistance(target)
                        vm.prepareMapPreview(context, target,
                            if (flowMode == "track") "计划轨迹预览" else "绘制线路预览") { intent ->
                            if (intent == null) flowStage = RunFlowStage.IDLE
                            else {
                                flowStage = RunFlowStage.PREVIEW
                                pendingRouteIntent = intent
                            }
                        }
                    }
                }
            }
            RunFlowStage.PREVIEW -> flowStage = RunFlowStage.READY_TO_START
            else -> Unit
        }
    }
    LaunchedEffect(pendingRouteIntent) {
        pendingRouteIntent?.let {
            pendingRouteIntent = null
            routeEditor.launch(it)
        }
    }
    val locked = run.isActive || run.phase == RunPhase.PAUSED || run.phase == RunPhase.NEEDS_REVIEW || run.writeInFlight
    val editable = !app.loading && !app.busy && !app.starting && !records.loading && !locked && pendingPermissionAction == null
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingPermissionAction
        when (action) {
            "start" -> { flowStage = RunFlowStage.IDLE; vm.start() }
            "resume" -> vm.resume()
        }
        if (!granted) vm.notificationDenied()
    }
    val withNotifications: (String) -> Unit = { action ->
        keyboard?.hide()
        focus.clearFocus()
        if (pendingPermissionAction == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingPermissionAction = action
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                if (action == "start") { flowStage = RunFlowStage.IDLE; vm.start() } else vm.resume()
            }
        }
    }
    val home = app.user != null || run.taskId != null || run.phase != RunPhase.IDLE
    val previewRoute: () -> Unit = {
        val target = app.settings.targetKm.toDoubleOrNull()
        if (target != null && target.isFinite() && target in 0.1..50.0) {
            vm.prepareMapPreview(context, target, "线路预览") { intent ->
                if (intent != null) {
                    flowStage = RunFlowStage.PREVIEW
                    pendingRouteIntent = intent
                }
            }
        }
    }
    val previewCurrent: () -> Unit = {
        vm.prepareCurrentMapPreview(context, "本次任务轨迹") { intent ->
            if (intent != null) {
                flowStage = RunFlowStage.PREVIEW
                pendingRouteIntent = intent
            }
        }
    }
    val beginEditor: (String) -> Unit = { mode ->
        if (editable) {
            keyboard?.hide(); focus.clearFocus()
            flowMode = mode
            flowStage = RunFlowStage.EDITING
            routeEditor.launch(vm.openRouteEditorIntent(context, mode))
        }
    }
    val trackRoute: () -> Unit = { beginEditor("track") }
    val beginSetup: () -> Unit = {
        if (editable) {
            flowTarget = app.settings.targetKm
            flowStage = RunFlowStage.CHOOSE_MODE
        }
    }
    BackHandler(enabled = home && selectedTab != 0) { selectedTab = 0 }

    // OverlayDialog registers with the nearest Scaffold's overlay host. Invoke this
    // content inside each Scaffold, never beside the Scaffold in the parent tree.
    val dialogs: @Composable () -> Unit = {
        OverlayDialog(show = flowStage == RunFlowStage.CHOOSE_MODE, title = "选择线路方式",
            onDismissRequest = { flowStage = RunFlowStage.IDLE }) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("跑道模式", MiuixIcons.MapAlbum, {
                    flowTarget = app.settings.targetKm
                    flowStage = RunFlowStage.TARGET
                }, primary = true, modifier = Modifier.fillMaxWidth())
                ActionButton("绘制线路", MiuixIcons.MapAlbum, { beginEditor("draw") }, modifier = Modifier.fillMaxWidth())
            }
        }
        OverlayDialog(show = flowStage == RunFlowStage.TARGET, title = "设置目标里程",
            onDismissRequest = { flowStage = RunFlowStage.IDLE }) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(flowTarget, { flowTarget = it }, "目标距离（公里）", editable)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = { flowStage = RunFlowStage.IDLE }, modifier = Modifier.weight(1f))
                    TextButton(text = "确定", onClick = {
                        val target = flowTarget.toDoubleOrNull()
                        if (target != null && target.isFinite() && target in 0.1..50.0) {
                            vm.setTargetDistance(target)
                            beginEditor("track")
                        } else vm.setTargetDistance(target ?: Double.NaN)
                    }, enabled = editable, modifier = Modifier.weight(1f))
                }
            }
        }
        val stopTask = run.taskId
        val canConfirmStop = run.phase == RunPhase.PAUSED && !run.writeInFlight
        ConfirmationDialog(
            show = run.stopRequested && run.phase != RunPhase.NEEDS_REVIEW,
            title = "停止并作废本次记录？",
            confirmText = if (canConfirmStop) "作废" else "等待暂停…",
            onConfirm = { vm.confirmStop(stopTask) }, onDismiss = vm::dismissStop, dismissText = "取消", enabled = canConfirmStop,
        )
        ConfirmationDialog(
            show = clearTask != null && clearTask == run.taskId,
            title = "清除本地状态？",
            confirmText = "清除", onConfirm = { vm.clearLocal(clearTask); clearTask = null },
            onDismiss = { clearTask = null }, dismissText = "取消",
            enabled = run.phase == RunPhase.NEEDS_REVIEW || !run.writeInFlight,
        )
        ConfirmationDialog(
            show = logoutDialog, title = "退出登录？",
            confirmText = "退出登录", onConfirm = { logoutDialog = false; vm.logout() },
            onDismiss = { logoutDialog = false }, enabled = editable,
        )
        MapSettingsDialog(mapSettingsDialog, mapSettings, vm, onDismiss = { mapSettingsDialog = false })
    }
    LaunchedEffect(run.taskId) { if (clearTask != run.taskId) clearTask = null }

    if (home) {
        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val tabs = listOf("跑步" to MiuixIcons.Stopwatch, "设置" to MiuixIcons.Settings, "记录" to MiuixIcons.ListView)
        val name = app.user?.name?.takeIf { it.isNotBlank() } ?: run.studentName.takeIf { it.isNotBlank() } ?: "校园跑步"
        val number = app.user?.studentNumber ?: run.studentNumber
        Scaffold(
            topBar = {
                TopAppBar(title = tabs[selectedTab].first,
                    subtitle = if (number.isNotBlank()) "$name · $number" else "$name · 本机任务",
                    scrollBehavior = scrollBehavior,
                    actions = { })
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = selectedTab == index, onClick = {
                            keyboard?.hide(); focus.clearFocus(); selectedTab = index
                        }, icon = icon, label = label)
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                key(selectedTab) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(app.error, records.selected?.record?.recordId) {
                        if (app.error != null || selectedTab == 2) scroll.animateScrollTo(0)
                    }
                    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)
                        .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppMessages(app)
                        when (selectedTab) {
                            0 -> {
                                if (run.taskId != null || run.phase != RunPhase.IDLE) {
                                    RuntimePanel(run, vm, pendingPermissionAction != null,
                                        onResume = { withNotifications("resume") }, onClear = { clearTask = run.taskId }, onPreview = previewCurrent)
                                }
                                if (!locked) {
                                    if (app.user != null) {
                                        val ready = flowStage == RunFlowStage.READY_TO_START
                                        ActionButton("开始跑步", MiuixIcons.Play,
                                            { if (ready) withNotifications("start") else beginSetup() },
                                            enabled = editable, primary = true, modifier = Modifier.fillMaxWidth())
                                        RankingPanel(ranking, vm)
                                    } else LoginCard(app, editable, vm, login)
                            }
                            }
                            1 -> {
                                if (run.taskId != null) {
                                    SectionCard("当前任务", MiuixIcons.Stopwatch) {
                                        Text(phaseLabel(run.phase), style = MiuixTheme.textStyles.subtitle)
                                        SupportingText(run.message)
                                        ActionButton("查看进度", MiuixIcons.Stopwatch, { selectedTab = 0 }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                SectionCard("高德地图设置", MiuixIcons.Location) {
                                    ActionButton("设置地图 Key", MiuixIcons.Location, { mapSettingsDialog = true },
                                        enabled = editable, primary = true, modifier = Modifier.fillMaxWidth())
                                }
                                SectionCard("账号设置", MiuixIcons.Contacts) {
                                    ActionButton("退出登录", MiuixIcons.Back, { logoutDialog = true },
                                        enabled = editable, destructive = true, modifier = Modifier.fillMaxWidth())
                                }
                                if (locked) MessageCard("任务进行中、已暂停或待核对时，地图设置暂不可修改。")
                            }
                            else -> RecordsPanel(app, vm, records, canDelete = !locked)
                        }
                        if (selectedTab == 2) LogPanel(app.events)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            dialogs()
        }
    } else {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(MiuixIcons.Stopwatch, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(76.dp))
                        Text("校园跑步", style = MiuixTheme.textStyles.title1)
                    }
                    AppMessages(app)
                    if (app.loading) {
                        SectionCard(if (app.busy) "正在登录" else "读取本机设置", MiuixIcons.Contacts) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    } else {
                        LoginCard(app, editable, vm, login)
                    }
                }
            }
            dialogs()
        }
    }
}

@Composable
private fun RankingPanel(state: RankingUiState, vm: AppViewModel) {
    SectionCard("班级排行榜", MiuixIcons.ListView) {
        state.currentUser?.let { current ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("我的排名", style = MiuixTheme.textStyles.subtitle)
                    SupportingText(current.user?.name?.takeIf(String::isNotBlank) ?: "当前账号")
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(current.rank?.let { "第${it}名" } ?: "未上榜", color = MiuixTheme.colorScheme.primary)
                    SupportingText(rankingDistance(current.user?.totalDistance))
                }
            }
        }
        if (state.loading && state.entries.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (state.error != null) {
            SupportingText("排行榜加载失败：${state.error}")
        } else if (state.entries.isEmpty()) {
        } else {
            state.entries.forEach { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val rankColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    Text(entry.rank.toString(), color = rankColor, style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.width(32.dp))
                    Text(entry.user.name.ifBlank { "匿名用户" }, Modifier.weight(1f))
                    Text(rankingDistance(entry.user.totalDistance), color = MiuixTheme.colorScheme.primary)
                }
            }
        }
        ActionButton(if (state.loading && state.entries.isNotEmpty()) "刷新中…" else "刷新",
            MiuixIcons.ListView, vm::refreshRanking, enabled = !state.loading, modifier = Modifier.fillMaxWidth())
    }
}

private fun rankingDistance(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
    String.format(Locale.US, "%.2f km", it)
} ?: "—"

@Composable
private fun AppMessages(app: AppUiState) {
    app.error?.let { MessageCard(it, error = true) }
    app.notice?.let { MessageCard(it) }
    if (!app.loading && (app.busy || app.starting)) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun LoginCard(app: AppUiState, enabled: Boolean, vm: AppViewModel, onLogin: () -> Unit) {
    SectionCard("登录账号", MiuixIcons.Contacts) {
        TextField(value = app.settings.token, onValueChange = vm::updateToken, label = "Token", singleLine = true,
            enabled = enabled, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (enabled) onLogin() }),
            leadingIcon = { Icon(MiuixIcons.Lock, null, modifier = Modifier.padding(start = 14.dp, end = 8.dp).size(20.dp)) },
            modifier = Modifier.fillMaxWidth())
        ActionButton(if (app.busy) "验证中…" else "登录", MiuixIcons.Forward, onLogin,
            enabled = enabled, primary = true, modifier = Modifier.fillMaxWidth())
}
}


@Composable
private fun RuntimePanel(run: RunUiState, vm: AppViewModel, awaitingPermission: Boolean, onResume: () -> Unit, onClear: () -> Unit, onPreview: () -> Unit) {
    SectionCard(if (run.isActive) "正在跑步" else "当前跑步会话", MiuixIcons.Stopwatch) {
        run.targetKm?.let { DistanceNumber(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${run.completedChunks} / ${run.totalChunks}", style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(countdown(run), style = MiuixTheme.textStyles.title1)
            }
        }
        if (run.totalChunks > 0) {
            LinearProgressIndicator(progress = (run.completedChunks.toFloat() / run.totalChunks).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
        } else if (run.isActive) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(phaseLabel(run.phase), style = MiuixTheme.textStyles.subtitle,
            color = if (run.phase == RunPhase.NEEDS_REVIEW) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
        Text(run.message, style = MiuixTheme.textStyles.body2,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        SupportingText("已规划 ${run.trackPoints} 个轨迹点")
        ActionButton("预览轨迹", MiuixIcons.Location, onPreview, modifier = Modifier.fillMaxWidth())
        run.error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
        if (run.phase == RunPhase.NEEDS_REVIEW) {
            MessageCard("写入结果未知或本地状态无法恢复。为避免重复记录，不会自动重试、恢复或作废，请先到官方平台核对。", error = true)
            ActionButton("清除本地", MiuixIcons.Delete, onClear,
                destructive = true, modifier = Modifier.fillMaxWidth())
        } else {
            if (run.isActive && !run.stopRequested) {
                ActionButton(if (run.pauseRequested) "等待暂停…" else "暂停", MiuixIcons.Pause, vm::pause,
                    enabled = !run.pauseRequested, modifier = Modifier.fillMaxWidth())
            }
            if (run.phase == RunPhase.PAUSED && !run.stopRequested) {
                ActionButton("恢复", MiuixIcons.Play, onResume, enabled = run.canResume && !awaitingPermission,
                    primary = true, modifier = Modifier.fillMaxWidth())
            }
            if ((run.isActive || run.phase == RunPhase.PAUSED) && !run.stopRequested) {
                ActionButton("停止并作废", MiuixIcons.Close, vm::requestStop,
                    destructive = true, modifier = Modifier.fillMaxWidth())
            }
            if (run.phase == RunPhase.COMPLETED || run.phase == RunPhase.FAILED) {
                TextButton(text = "清除摘要", onClick = onClear, modifier = Modifier.fillMaxWidth())
            }
        }
        if (run.taskId != null) {
            HorizontalDivider()
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupportingText("任务编号  ${run.taskId}")
                    run.recordId?.let { SupportingText("平台记录  $it") }
                    SupportingText("最后确认分块  ${run.lastChunkSeq}")
                    if (run.lastChunkHash.isNotBlank()) SupportingText("分块哈希  ${run.lastChunkHash}")
                }
            }
        }
    }
}

@Composable
private fun DistanceNumber(distance: Double?) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(distance?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—",
            style = MiuixTheme.textStyles.title1.copy(fontSize = 44.sp, lineHeight = 52.sp),
            fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun countdown(run: RunUiState): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.nextScheduledMs, run.phase) {
        while (run.isActive && run.nextScheduledMs != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return when {
        run.phase == RunPhase.PAUSED -> "已暂停"
        !run.isActive || run.nextScheduledMs == null -> "—"
        else -> {
            val seconds = ((run.nextScheduledMs - now).coerceAtLeast(0L) + 999L) / 1_000L
            if (seconds == 0L) "准备上传" else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}

@Composable
private fun MapSettingsDialog(show: Boolean, state: MapSettingsUiState, vm: AppViewModel, onDismiss: () -> Unit) {
    var key by remember(state.settings.mapKey, show) { mutableStateOf(state.settings.mapKey) }
    var security by remember(state.settings.securityCode, show) { mutableStateOf(state.settings.securityCode) }
    OverlayDialog(show = show, title = "设置高德地图",
        onDismissRequest = { if (!state.saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = key, onValueChange = { key = it }, label = "Web JS Key", singleLine = true,
                enabled = !state.saving, modifier = Modifier.fillMaxWidth())
            TextField(value = security, onValueChange = { security = it }, label = "securityJsCode", singleLine = true,
                enabled = !state.saving, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            state.error?.let { MessageCard(it, error = true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, enabled = !state.saving, modifier = Modifier.weight(1f))
                ActionButton(if (state.saving) "保存中…" else "保存", MiuixIcons.Ok,
                    { vm.saveMapSettings(key, security, onDismiss) },
                    enabled = !state.saving && (key.isBlank() == security.isBlank()), primary = true,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}


@Composable
private fun LogPanel(events: List<RunEvent>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        ArrowPreference(title = "执行日志",
            startAction = { Icon(MiuixIcons.Notes, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
            onClick = { expanded = !expanded })
    }
    OverlayDialog(show = expanded, title = "执行日志", onDismissRequest = { expanded = false }) {
        val state = rememberLazyListState()
        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 360.dp),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                SelectionContainer {
                    Text("${formatter.format(Date(event.timestamp))}  ${event.message}", style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        LaunchedEffect(events.size, expanded) { if (expanded && events.isNotEmpty()) state.animateScrollToItem(events.lastIndex) }
        TextButton(text = "关闭", onClick = { expanded = false }, modifier = Modifier.fillMaxWidth())
    }
}

private fun phaseLabel(phase: RunPhase): String = when (phase) {
    RunPhase.IDLE -> "尚未开始"
    RunPhase.STARTING -> "正在启动"
    RunPhase.PLANNING -> "规划轨迹"
    RunPhase.UPLOADING -> "实时上传"
    RunPhase.PAUSED -> "已暂停"
    RunPhase.NEEDS_REVIEW -> "需要核对"
    RunPhase.COMPLETED -> "已结束"
    RunPhase.FAILED -> "已停止 / 异常"
}
