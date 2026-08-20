package tv.hudl.fanplayer.kiosk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import tv.hudl.fanplayer.MainActivity
import tv.hudl.fanplayer.settings.SettingsStore

/**
 * Best-effort kiosk enforcement for ordinary sideloaded devices.
 *
 * This service intentionally does not request window-content access. True lockdown still requires
 * device-owner provisioning and Android lock task mode.
 */
class KioskAccessibilityService : AccessibilityService() {
    private val settingsStore by lazy { SettingsStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var foregroundPackage: String? = null
    private var lastReturnAttemptEpochMs = 0L

    private val relockAfterMaintenance = Runnable {
        if (settingsStore.isExtremeKioskEnforced() && !isAllowedPackage(foregroundPackage)) {
            returnToKiosk()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduleEnforcement()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val observedPackage = event?.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (observedPackage != null) foregroundPackage = observedPackage
        scheduleEnforcement()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!settingsStore.isExtremeKioskEnforced()) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_MENU -> true
            // Back must remain available for in-app navigation, especially when leaving playback.
            // If it ever closes the root activity, accessibility enforcement brings the kiosk back.
            KeyEvent.KEYCODE_BACK -> false
            else -> false
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun scheduleEnforcement() {
        handler.removeCallbacks(relockAfterMaintenance)
        if (!settingsStore.load().extremeKioskMode) return

        val unlockUntil = settingsStore.kioskUnlockUntilEpochMs()
        if (unlockUntil != null) {
            val delay = (unlockUntil - System.currentTimeMillis()).coerceAtLeast(0L) + 100L
            handler.postDelayed(relockAfterMaintenance, delay)
            return
        }
        if (!isAllowedPackage(foregroundPackage)) returnToKiosk()
    }

    private fun isAllowedPackage(candidate: String?): Boolean {
        if (candidate == packageName || candidate in SYSTEM_WINDOW_PACKAGES) return true
        return candidate != null && candidate == defaultInputMethodPackage()
    }

    private fun defaultInputMethodPackage(): String? = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)
            ?.packageName
    }.getOrNull()

    private fun returnToKiosk() {
        val now = System.currentTimeMillis()
        if (now - lastReturnAttemptEpochMs < RETURN_THROTTLE_MS) return
        lastReturnAttemptEpochMs = now

        val taskMoved = runCatching {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val task = manager.appTasks.firstOrNull()
            task?.moveToFront()
            task != null
        }.getOrDefault(false)
        if (taskMoved) return

        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    companion object {
        private const val RETURN_THROTTLE_MS = 750L
        private val SYSTEM_WINDOW_PACKAGES = setOf("android", "com.android.systemui")

        fun isEnabled(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val expected = ComponentName(context, KioskAccessibilityService::class.java)
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val service = info.resolveInfo?.serviceInfo
                service?.packageName == expected.packageName && service.name == expected.className
            }
        }.getOrDefault(false)
    }
}
