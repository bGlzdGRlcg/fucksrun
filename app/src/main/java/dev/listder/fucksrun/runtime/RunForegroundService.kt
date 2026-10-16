package dev.listder.fucksrun.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.listder.fucksrun.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** No task is reconstructed from an Intent, and Android must never restart a write automatically. */
class RunForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runtime: RunRuntime
    private lateinit var notifications: NotificationManager
    private var taskId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var generation = 0L
    private val pauseBeforeWakeTimeout = Runnable { runtime.pause() }

    override fun onCreate() {
        super.onCreate()
        runtime = RunRuntime.get(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "跑步任务", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示跑步上传进度，并提供暂停和停止确认入口"
            setShowBadge(false)
        })
        scope.launch {
            runtime.state.collectLatest { state ->
                if (state.taskId != taskId || taskId == null || !foreground) return@collectLatest
                val notification = notification(state)
                if (state.isActive) {
                    if (canNotify()) notifications.notify(NOTIFICATION_ID, notification)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground = false
                    releaseWakeLock()
                    if (canNotify()) notifications.notify(NOTIFICATION_ID, notification)
                    // Keep the started instance reusable while paused, but hold no foreground status or WakeLock.
                    // Stopping it here races a quick resume/discard with its queued destruction.
                    if (state.phase != RunPhase.PAUSED) stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = intent?.getStringExtra(EXTRA_TASK_ID)
        val state = runtime.state.value
        if (incoming.isNullOrBlank() || incoming != state.taskId) {
            if (taskId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (incoming == taskId && state.isActive) runtime.pause()
                else if (taskId == null) stopSelf(startId)
            }
            ACTION_START -> {
                if (!state.isActive) {
                    if (taskId == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                taskId = incoming
                generation = intent.getLongExtra(EXTRA_GENERATION, 0L)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, notification(state))
                    }
                    foreground = true
                    acquireWakeLock()
                    // Creation is a write too: do not start even getUserInfo/create before foreground setup.
                    runtime.serviceReady(incoming, generation)
                } catch (_: Exception) {
                    runtime.serviceLost(incoming, generation)
                    releaseWakeLock()
                    stopSelf(startId)
                }
            }
            else -> if (taskId == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        taskId?.let { runtime.serviceLost(it, generation) }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (foreground) taskId?.let { runtime.serviceLost(it, generation) }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:run-upload").apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        handler.removeCallbacks(pauseBeforeWakeTimeout)
        handler.postDelayed(pauseBeforeWakeTimeout, WAKE_TIMEOUT_MS - 60_000)
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(pauseBeforeWakeTimeout)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(state: RunUiState): Notification {
        val id = state.taskId ?: "unknown"
        val phase = when (state.phase) {
            RunPhase.IDLE -> "尚未开始"
            RunPhase.STARTING -> "准备中"
            RunPhase.PLANNING -> "规划轨迹"
            RunPhase.UPLOADING -> "上传中"
            RunPhase.PAUSED -> "已暂停"
            RunPhase.NEEDS_REVIEW -> "需要核对"
            RunPhase.COMPLETED -> "已完成"
            RunPhase.FAILED -> "已停止"
        }
        val progress = if (state.totalChunks > 0) "${state.completedChunks}/${state.totalChunks} 分片 · " else ""
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("校园跑 · $phase")
            .setContentText(progress + state.message)
            .setStyle(Notification.BigTextStyle().bigText(progress + state.message))
            .setContentIntent(openActivity(id, false))
            .setOnlyAlertOnce(true)
            .setOngoing(state.isActive)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (state.isActive) {
            builder.setProgress(state.totalChunks, state.completedChunks, state.totalChunks == 0)
            val pause = PendingIntent.getService(this, 1,
                Intent(this, RunForegroundService::class.java).setAction(ACTION_PAUSE)
                    .setData(Uri.parse("fucksrun://task/$id/pause")).putExtra(EXTRA_TASK_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "暂停", pause).build())
        }
        if (state.isActive || state.phase == RunPhase.PAUSED) {
            // Never discard from a service action: the Activity owns the explicit confirmation.
            builder.addAction(Notification.Action.Builder(null, "停止…", openActivity(id, true)).build())
        }
        return builder.build()
    }

    private fun openActivity(id: String, confirmStop: Boolean): PendingIntent = PendingIntent.getActivity(
        this, if (confirmStop) 2 else 0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(Uri.parse("fucksrun://task/$id/${if (confirmStop) "stop" else "open"}"))
            .putExtra(EXTRA_TASK_ID, id).putExtra(EXTRA_CONFIRM_STOP, confirmStop),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "dev.listder.fucksrun.RUN_START"
        const val ACTION_PAUSE = "dev.listder.fucksrun.RUN_PAUSE"
        const val EXTRA_TASK_ID = "run_task_id"
        const val EXTRA_CONFIRM_STOP = "run_confirm_stop"
        const val EXTRA_GENERATION = "run_service_generation"
        private const val CHANNEL_ID = "run-upload"
        private const val NOTIFICATION_ID = 4101
        private const val WAKE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
