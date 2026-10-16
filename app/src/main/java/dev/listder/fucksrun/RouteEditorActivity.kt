package dev.listder.fucksrun

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.listder.fucksrun.protocol.CanonicalJson
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import dev.listder.fucksrun.protocol.DrawnRoute
import dev.listder.fucksrun.protocol.LatLon
import dev.listder.fucksrun.storage.RouteSettings
import dev.listder.fucksrun.storage.RouteSettingsStore
import dev.listder.fucksrun.ui.SupportingText
import dev.listder.fucksrun.ui.theme.FucksrunTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Local original editor, with its HTML controls and independent AMap Web JS credentials. */
class RouteEditorActivity : ComponentActivity() {
    private var mapView: WebView? = null
    private lateinit var store: RouteSettingsStore
    private var viewer = false
    private var locationRequestJob: Job? = null
    private var initialLocation by mutableStateOf<CurrentLocation?>(null)
    private var latestLocation: CurrentLocation? = null
    private var locationLoading by mutableStateOf(false)
    private var locationError by mutableStateOf<String?>(null)
    private var locationNotice by mutableStateOf<String?>(null)
    private var mapLoadError by mutableStateOf<String?>(null)
    private var mapLoaded = false
    private var mapWatchdog: Job? = null
    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!viewer) {
            if (hasLocationPermission()) requestCurrentLocation()
            else {
                locationLoading = false
                locationError = "需要定位权限才能打开线路规划，请允许定位后重试"
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = RouteSettingsStore(this)
        val loaded = runCatching { store.load() }
        val input = runCatching {
            val json = intent.getStringExtra("config") ?: "{}"
            require(json.length < 1_500_000) { "地图配置数据过大" }
            CanonicalJson.parseObject(json)
        }
        viewer = input.getOrNull()?.get("viewer") in listOf("live", "finished")
        if (!viewer && loaded.getOrDefault(RouteSettings()).configured()) requestCurrentLocation()
        setContent {
            FucksrunTheme {
                var settings by remember { mutableStateOf(loaded.getOrDefault(RouteSettings())) }
                var showSettings by remember { mutableStateOf(!settings.configured()) }
                var settingsError by remember { mutableStateOf(loaded.exceptionOrNull()?.message) }
                var document by remember { mutableStateOf<String?>(null) }
                var pageError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(settings, initialLocation) {
                    document = null
                    pageError = null
                    mapWatchdog?.cancel()
                    mapLoadError = null
                    mapLoaded = false
                    try {
                        document = withContext(Dispatchers.IO) {
                            val config = configuration(input.getOrThrow(), settings)
                            val script = "<script>window.ROUTE_CONFIG=${CanonicalJson.compact(config).replace("<", "\\u003c")};</script>"
                            assets.open("route-editor.html").bufferedReader().use { it.readText() }
                                .replace("<!-- CONFIG -->", script)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        pageError = error.message ?: "无法打开地图"
                    }
                }
                Scaffold(topBar = {
                    SmallTopAppBar(title = if (viewer) "线路预览" else "线路规划", navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(MiuixIcons.Back, "返回") }
                    })
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.Center) {
                        when {
                            !settings.configured() -> Column(Modifier.padding(28.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                settingsError?.let { SupportingText(it) }
                                TextButton(text = "填写地图配置", onClick = { showSettings = true })
                            }
                            !viewer && initialLocation == null && locationError != null -> Column(
                                Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                SupportingText(locationError.orEmpty())
                                TextButton(text = "重试定位", onClick = { requestCurrentLocation() })
                            }
                            !viewer && initialLocation == null -> Column(
                                Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                SupportingText("正在获取当前位置…")
                            }
                            pageError != null -> SupportingText(pageError.orEmpty(), Modifier.padding(24.dp))
                            document == null -> CircularProgressIndicator()
                            else -> Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    key(document) { EditorWebView(document!!) }
                                }
                                mapLoadError?.let { SupportingText(it, Modifier.padding(16.dp)) }
                                locationError?.let {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        SupportingText(it)
                                        TextButton(text = "重试定位", onClick = { requestCurrentLocation() })
                                    }
                                }
                            }
                        }
                    }
                    // Miuix dialogs must be descendants of their Scaffold's overlay host.
                    CredentialsDialog(showSettings, settings, settingsError,
                        onDismiss = { showSettings = false },
                        onSave = { mapKey, securityCode ->
                            try {
                                val updated = settings.copy(mapKey = mapKey.trim(), securityCode = securityCode.trim())
                                store.save(updated)
                                settings = updated
                                settingsError = null
                                showSettings = false
                                if (!viewer && updated.configured() && initialLocation == null) requestCurrentLocation()
                            } catch (error: Exception) {
                                settingsError = error.message ?: "地图配置保存失败"
                            }
                        })
                }
            }
        }
    }

    private fun configuration(input: Map<String, Any?>, settings: RouteSettings): Map<String, Any?> {
        // Only drawing and viewing settings cross into the page; no account, check-in or protocol state.
        val allowed = setOf("fenceName", "preview", "draft", "fixedDraft", "viewer", "liveTrack", "lockedMode",
            "plannedPath", "liveElapsed", "liveRunning", "liveStartMs", "liveStats", "liveTargetDistance", "liveTargetPace", "previewNote")
        return LinkedHashMap(input.filterKeys { it in allowed }).apply {
            put("key", settings.mapKey)
            put("securityCode", settings.securityCode)
            put("nativeControls", false)
            put("fenceCode", "local")
            put("darkTheme", resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            if (!viewer) put("currentLocation", requireNotNull(initialLocation).asMap())
            if (!containsKey("draft")) settings.draftJson?.takeIf { it.isNotBlank() }?.let { put("draft", DrawnRoute.parse(it).raw) }
            get("draft")?.let {
                val canonical = CanonicalJson.compact(it)
                val raw = CanonicalJson.parseObject(canonical)
                put("draft", if (raw["path"] == null) raw else DrawnRoute.parse(canonical).raw)
            }
            get("fixedDraft")?.let { put("fixedDraft", DrawnRoute.parse(CanonicalJson.compact(it)).raw) }
            val draft = get("draft") as? Map<*, *>
            if (!containsKey("fixedDraft") && draft?.get("fixed") == true) put("fixedDraft", draft)
            if (!containsKey("preview")) {
                val source = if (viewer) get("liveTrack") as? List<*> else null
                put("preview", source?.mapNotNull { point -> (point as? List<*>)?.take(2) } ?: emptyList<List<Any?>>())
            }
            putIfAbsent("previewNote", "原版地图绘制。跑道模式请依次选择两个弯道尖端和一侧直道点；保存的线路沿原路径闭环，不添加位置偏移。")
        }
    }

    private fun requestCurrentLocation() {
        if (viewer || isFinishing || isDestroyed) return
        locationRequestJob?.cancel()
        locationLoading = true
        locationError = null
        if (!hasLocationPermission()) {
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        locationRequestJob = lifecycleScope.launch {
            try {
                val current = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { awaitFreshLocation() }
                    ?: throw LocationFailure("定位超时，请确认系统定位已开启后重试")
                latestLocation = current
                locationLoading = false
                locationNotice = when {
                    hasApproximateOnlyPermission() -> "当前仅授予近似定位权限，地图位置可能存在偏差。"
                    current.accuracy > 100 -> "当前定位精度约 ${current.accuracy.toInt()} 米，可再次定位或在地图上调整。"
                    else -> "已定位到当前位置 · 精度约 ${current.accuracy.toInt()} 米"
                }
                if (initialLocation == null) initialLocation = current else sendLocationToPage(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                locationLoading = false
                locationError = error.message ?: "无法获取当前位置，请重试"
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasApproximateOnlyPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun awaitFreshLocation(): CurrentLocation = suspendCancellableCoroutine { continuation ->
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val precise = !hasApproximateOnlyPermission()
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { precise || it != LocationManager.GPS_PROVIDER }
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            continuation.resumeWith(Result.failure(LocationFailure("系统定位服务未开启，请开启 GPS 或网络定位后重试")))
            return@suspendCancellableCoroutine
        }

        val completed = AtomicBoolean(false)
        lateinit var listener: LocationListener
        fun finish(result: Result<CurrentLocation>) {
            if (!completed.compareAndSet(false, true)) return
            runCatching { manager.removeUpdates(listener) }
            continuation.resumeWith(result)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
                    location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
                val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                if (ageNanos > LOCATION_TIMEOUT_MS * 1_000_000L || ageNanos < -1_000_000_000L) return
                finish(Result.success(CurrentLocation(location.latitude, location.longitude,
                    if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0)))
            }

            @Deprecated("Deprecated in Android API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) runCatching { manager.removeUpdates(listener) }
        }
        var registered = false
        for (provider in providers) {
            if (completed.get() || !continuation.isActive) break
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
                // An optional provider may require precise permission; other allowed providers remain usable.
            } catch (_: IllegalArgumentException) {
                // Provider availability can change after the enabled-provider snapshot.
            }
        }
        if (completed.get()) runCatching { manager.removeUpdates(listener) }
        else if (!registered) finish(Result.failure(LocationFailure("当前没有可用的定位提供商，请检查系统定位和应用权限")))
    }

    private fun sendLocationToPage(location: CurrentLocation) {
        val payload = CanonicalJson.compact(location.asMap()).replace("<", "\\u003c")
        mapView?.evaluateJavascript("window.RouteEditor && window.RouteEditor.setCurrentLocation($payload);", null)
    }


    private data class CurrentLocation(val latitude: Double, val longitude: Double, val accuracy: Double) {
        fun asMap(): Map<String, Any> = linkedMapOf("latitude" to latitude, "longitude" to longitude, "accuracy" to accuracy)
    }

    private class LocationFailure(message: String) : Exception(message)

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun EditorWebView(document: String) {
        val background = MiuixTheme.colorScheme.background.toArgb()
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                mapView = this
                setBackgroundColor(background)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setGeolocationEnabled(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!viewer) latestLocation?.let(::sendLocationToPage)
                        mapWatchdog?.cancel()
                        mapWatchdog = lifecycleScope.launch {
                            kotlinx.coroutines.delay(25_000)
                            if (!mapLoaded && !isFinishing && !isDestroyed) {
                                mapLoadError = "高德地图加载超时，请检查网络、Web JS Key 和 securityJsCode 后重试"
                            }
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    @Deprecated("Legacy WebView navigation callback")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
                }
                addJavascriptInterface(RouteBridge(), "RouteNative")
                loadDataWithBaseURL("https://runner.local/", document, "text/html", "UTF-8", null)
            }
        }, onRelease = { view ->
            mapWatchdog?.cancel()
            if (mapView === view) mapView = null
            view.removeJavascriptInterface("RouteNative")
            view.stopLoading()
            view.destroy()
        })
    }

    /** These are the only two methods exposed to the trusted editor document. */
    private inner class RouteBridge {
        @JavascriptInterface
        fun requestCurrentLocation() { this@RouteEditorActivity.runOnUiThread { this@RouteEditorActivity.requestCurrentLocation() } }
        @JavascriptInterface
        fun mapReady() { this@RouteEditorActivity.runOnUiThread { mapLoaded = true; mapLoadError = null; mapWatchdog?.cancel() } }
        @JavascriptInterface
        fun mapFailed(message: String) { this@RouteEditorActivity.runOnUiThread { mapLoadError = message.take(300); mapWatchdog?.cancel() } }
        @JavascriptInterface
        fun saveRoute(json: String) = accept(json, fixed = false)
        @JavascriptInterface
        fun saveFixedRoute(json: String) = accept(json, fixed = true)
    }

    private fun accept(json: String, fixed: Boolean) {
        if (viewer) return
        val accepted = runCatching {
            val raw = CanonicalJson.parseObject(json)
            val mode = raw["mode"]?.toString() ?: "manual"
            val anchors = raw["anchors"] as? List<*> ?: emptyList<Any?>()
            val path = raw["path"] as? List<*> ?: emptyList<Any?>()
            require(path.size >= 2) { "请先完成线路规划" }
            if (mode == "track") require(anchors.size == 3 && raw["closed"] == true) { "跑道模式请完成两个弯道尖端和一侧直道点" }
            else require(anchors.size >= 2) { "请至少添加两个线路点" }
            val parsed = DrawnRoute.parse(json)
            CanonicalJson.compact(if (fixed) parsed.raw + ("fixed" to true) else parsed.raw)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                val draft = accepted.getOrThrow()
                store.save(store.load().copy(draftJson = draft))
                if (fixed) {
                    mapView?.evaluateJavascript("window.RouteEditor.fixedSaved(${draft.replace("<", "\\u003c")});", null)
                } else {
                    setResult(Activity.RESULT_OK, Intent().putExtra("route", draft))
                    finish()
                }
            } catch (error: Exception) {
                val message = CanonicalJson.compact(error.message ?: "线路保存失败").replace("<", "\\u003c")
                mapView?.evaluateJavascript("window.RouteEditor.showError($message);", null)
            }
        }
    }

    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onStop() {
        if (locationRequestJob?.isActive == true) {
            locationRequestJob?.cancel()
            locationLoading = false
            locationError = "定位已暂停，返回后可点击定位重试。"
        }
        super.onStop()
    }
    override fun onDestroy() {
        locationRequestJob?.cancel()
        locationRequestJob = null
        // AndroidView.onRelease owns destruction; revoke the bridge immediately on Activity teardown.
        mapView?.apply { removeJavascriptInterface("RouteNative"); stopLoading() }
        mapView = null
        super.onDestroy()
    }

    companion object {
        private const val LOCATION_TIMEOUT_MS = 15_000L
        fun createEditorIntent(context: Context, mode: String): Intent {
            require(mode == "track" || mode == "draw") { "线路模式错误" }
            val draft = linkedMapOf<String, Any?>("mode" to mode, "closed" to (mode == "track"))
            return Intent(context, RouteEditorActivity::class.java).putExtra(
                "config", CanonicalJson.compact(linkedMapOf("draft" to draft, "lockedMode" to mode)))
        }
        /** Read-only map viewer; the independent offline Canvas preview needs no map credentials. */
        fun createPreviewIntent(context: Context, points: List<LatLon>, title: String): Intent {
            val config = linkedMapOf("viewer" to "finished", "fenceName" to title,
                "liveTrack" to points.map { listOf(it.longitude, it.latitude, 0) },
                "liveElapsed" to 0, "liveRunning" to false,
                "previewNote" to "仅查看此条轨迹，不会创建或提交跑步记录。")
            return Intent(context, RouteEditorActivity::class.java).putExtra("config", CanonicalJson.compact(config))
        }
    }
}

private fun RouteSettings.configured(): Boolean = mapKey.isNotBlank() && securityCode.isNotBlank()

@Composable
private fun CredentialsDialog(show: Boolean, settings: RouteSettings, error: String?, onDismiss: () -> Unit,
    onSave: (String, String) -> Unit) {
    var mapKey by remember(settings.mapKey, show) { mutableStateOf(settings.mapKey) }
    var securityCode by remember(settings.securityCode, show) { mutableStateOf(settings.securityCode) }
    OverlayDialog(show = show, title = "配置高德地图",
        onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = mapKey, onValueChange = { mapKey = it }, label = "Web 端 Key", singleLine = true,
                modifier = Modifier.fillMaxWidth())
            TextField(value = securityCode, onValueChange = { securityCode = it }, label = "安全密钥 securityJsCode", singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { SupportingText(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(text = "保存", enabled = (mapKey.isBlank() && securityCode.isBlank()) || (mapKey.isNotBlank() && securityCode.isNotBlank()),
                    onClick = { onSave(mapKey, securityCode) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
