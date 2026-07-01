package me.rerere.rikkahub.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.EXTRA_OPEN_USAGE_TRACKER
import me.rerere.rikkahub.R
import me.rerere.rikkahub.USAGE_LIMIT_REMINDER_CHANNEL_ID
import me.rerere.rikkahub.USAGE_REMINDER_MONITOR_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.usagetracker.UsageReminderAppState
import me.rerere.usagetracker.UsageReminderConfig
import me.rerere.usagetracker.UsageReminderRule
import me.rerere.usagetracker.UsageReminderState
import me.rerere.usagetracker.UsageStatsPeriod
import me.rerere.usagetracker.UsageStatsReader
import me.rerere.usagetracker.todayKey
import org.koin.android.ext.android.inject
import kotlin.math.absoluteValue

private const val TAG = "UsageReminderService"

class UsageReminderService : Service() {
    private val settingsStore by inject<SettingsStore>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reader: UsageStatsReader
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        reader = UsageStatsReader(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_IGNORE_TODAY -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (packageName != null) {
                    scope.launch { ignoreToday(packageName) }
                }
                return START_STICKY
            }

            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (!hasNotificationPermission(this) || !reader.hasUsageAccess()) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification())
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                runCatching { checkUsageRules() }
                    .onFailure { Log.e(TAG, "checkUsageRules failed", it) }
                delay(CHECK_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun checkUsageRules() {
        val settings = settingsStore.settingsFlowRaw.first()
        val enabledRules = settings.usageReminderConfig.rules.filter { it.enabled }
        if (enabledRules.isEmpty() || !hasNotificationPermission(this) || !reader.hasUsageAccess()) {
            stopSelf()
            return
        }

        val today = todayKey()
        val state = settings.usageReminderState.takeIf { it.date == today } ?: UsageReminderState(date = today)
        val earliestKnownEvent = enabledRules
            .mapNotNull { rule -> state.appStates[rule.packageName]?.lastEventTimeMillis }
            .minOrNull()
            ?: (System.currentTimeMillis() - CHECK_LOOKBACK_MILLIS)
        val events = reader.loadForegroundEvents(
            startMillis = (earliestKnownEvent - 1_000L).coerceAtLeast(0L),
            endMillis = System.currentTimeMillis(),
        )

        val rulesByPackage = enabledRules.associateBy { it.packageName }
        val nextStates = state.appStates.toMutableMap()
        var changed = state.date != settings.usageReminderState.date

        for (event in events) {
            val rule = rulesByPackage[event.packageName] ?: continue
            val current = nextStates[event.packageName] ?: UsageReminderAppState()
            if (event.timestampMillis <= current.lastEventTimeMillis) continue

            val baseState = current.copy(lastEventTimeMillis = event.timestampMillis)
            if (baseState.ignored) {
                nextStates[event.packageName] = baseState
                changed = true
                continue
            }
            nextStates[event.packageName] = baseState
            changed = true
        }

        for (rule in enabledRules) {
            val current = nextStates[rule.packageName] ?: UsageReminderAppState()
            if (current.ignored) continue

            val usageMillis = reader.loadUsageMillis(rule.packageName, UsageStatsPeriod.Today)
            if (usageMillis < rule.thresholdMinutes * 60_000L) continue
            if (usageMillis <= current.lastReminderUsageMillis) continue

            val remindedState = current.copy(
                reminderCount = current.reminderCount + 1,
                lastReminderUsageMillis = usageMillis,
            )
            nextStates[rule.packageName] = remindedState
            sendLimitNotification(
                rule = rule,
                usageMillis = usageMillis,
                reminderCount = remindedState.reminderCount,
                reminderMessages = settings.usageReminderConfig.reminderMessages,
            )
            changed = true
        }

        if (changed) {
            settingsStore.update { current ->
                current.copy(
                    usageReminderState = UsageReminderState(
                        date = today,
                        appStates = nextStates,
                    )
                )
            }
        }
    }

    private suspend fun ignoreToday(packageName: String) {
        settingsStore.update { settings ->
            val today = todayKey()
            val state = settings.usageReminderState.takeIf { it.date == today } ?: UsageReminderState(date = today)
            val nextStates = state.appStates.toMutableMap()
            val current = nextStates[packageName] ?: UsageReminderAppState()
            nextStates[packageName] = current.copy(ignored = true)
            settings.copy(
                usageReminderState = state.copy(appStates = nextStates)
            )
        }
        NotificationManagerCompat.from(this).cancel(notificationIdFor(packageName))
    }

    private fun buildMonitorNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_USAGE_TRACKER,
            Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_USAGE_TRACKER, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, USAGE_REMINDER_MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.usage_reminder_monitor_title))
            .setContentText(getString(R.string.usage_reminder_monitor_desc))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun sendLimitNotification(
        rule: UsageReminderRule,
        usageMillis: Long,
        reminderCount: Int,
        reminderMessages: List<String>,
    ) {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_USAGE_TRACKER + notificationIdFor(rule.packageName),
            Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_USAGE_TRACKER, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val content = buildReminderContent(rule.label, usageMillis, reminderMessages)
        val builder = NotificationCompat.Builder(this, USAGE_LIMIT_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(
                getString(
                    R.string.usage_reminder_limit_title,
                    rule.label,
                    rule.thresholdMinutes,
                )
            )
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        if (reminderCount >= IGNORE_ACTION_START_COUNT) {
            builder.addAction(
                R.drawable.small_icon,
                getString(R.string.usage_reminder_ignore_today),
                PendingIntent.getService(
                    this,
                    REQUEST_IGNORE_BASE + notificationIdFor(rule.packageName),
                    Intent(this, UsageReminderService::class.java).apply {
                        action = ACTION_IGNORE_TODAY
                        putExtra(EXTRA_PACKAGE_NAME, rule.packageName)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        NotificationManagerCompat.from(this).notify(notificationIdFor(rule.packageName), builder.build())
    }

    private fun buildReminderContent(appLabel: String, usageMillis: Long, reminderMessages: List<String>): String {
        val message = reminderMessages.filter { it.isNotBlank() }.randomOrNull().orEmpty()
        return getString(
            R.string.usage_reminder_limit_content,
            appLabel,
            formatDuration(usageMillis),
            message,
        ).trimEnd()
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000L
        val hours = minutes / 60L
        val restMinutes = minutes % 60L
        return when {
            hours > 0L -> getString(R.string.usage_reminder_duration_hours, hours, restMinutes)
            minutes > 0L -> getString(R.string.usage_reminder_duration_minutes, minutes)
            millis > 0L -> getString(R.string.usage_reminder_duration_less_than_minute)
            else -> getString(R.string.usage_reminder_duration_zero)
        }
    }

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.usage_reminder.START"
        const val ACTION_STOP = "me.rerere.rikkahub.usage_reminder.STOP"
        const val ACTION_IGNORE_TODAY = "me.rerere.rikkahub.usage_reminder.IGNORE_TODAY"
        private const val EXTRA_PACKAGE_NAME = "packageName"
        private const val NOTIFICATION_ID_MONITOR = 200_100
        private const val REQUEST_OPEN_USAGE_TRACKER = 200_200
        private const val REQUEST_IGNORE_BASE = 200_300
        private const val CHECK_INTERVAL_MILLIS = 5 * 60_000L
        private const val CHECK_LOOKBACK_MILLIS = 60_000L
        private const val IGNORE_ACTION_START_COUNT = 3

        fun sync(context: Context, config: UsageReminderConfig) {
            val intent = Intent(context, UsageReminderService::class.java).apply {
                action = if (config.rules.any { it.enabled }) ACTION_START else ACTION_STOP
            }
            if (config.rules.any { it.enabled } && hasNotificationPermission(context)) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun hasNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun notificationIdFor(packageName: String): Int {
            return 210_000 + packageName.hashCode().absoluteValue % 10_000
        }
    }
}
