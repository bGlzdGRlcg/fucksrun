package dev.listder.fucksrun

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.listder.fucksrun.runtime.RunForegroundService
import dev.listder.fucksrun.ui.AppViewModel
import dev.listder.fucksrun.ui.FucksrunApp
import dev.listder.fucksrun.ui.theme.FucksrunTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by lazy {
        ViewModelProvider(this)[AppViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)
        setContent {
            FucksrunTheme { FucksrunApp(appViewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(RunForegroundService.EXTRA_CONFIRM_STOP, false) != true) return
        val taskId = intent.getStringExtra(RunForegroundService.EXTRA_TASK_ID) ?: return
        if (taskId.isNotBlank()) appViewModel.notificationStop(taskId)
        // Rotation must not re-open an already handled notification action.
        intent.removeExtra(RunForegroundService.EXTRA_CONFIRM_STOP)
    }
}
