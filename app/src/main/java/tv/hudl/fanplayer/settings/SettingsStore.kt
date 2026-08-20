package tv.hudl.fanplayer.settings

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Local-only settings. No account or cloud service is required. */
class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        organizationInput = preferences.getString(KEY_ORGANIZATION, "").orEmpty(),
        refreshIntervalSeconds = preferences.getLong(KEY_REFRESH_INTERVAL, 20L),
        autoPlayLiveEvents = preferences.getBoolean(KEY_AUTO_PLAY, true),
        interruptVodWhenLive = preferences.getBoolean(KEY_INTERRUPT_VOD, true),
        returnHomeAfterEvent = preferences.getBoolean(KEY_RETURN_HOME, true),
        launchOnBoot = true,
        keepScreenAwake = preferences.getBoolean(KEY_KEEP_AWAKE, true),
        extremeKioskMode = preferences.getBoolean(KEY_EXTREME_KIOSK, false)
    )

    fun save(settings: AppSettings) {
        val organizationChanged = preferences.getString(KEY_ORGANIZATION, "").orEmpty() !=
            settings.organizationInput
        preferences.edit()
            .putString(KEY_ORGANIZATION, settings.organizationInput)
            .putLong(KEY_REFRESH_INTERVAL, settings.refreshIntervalSeconds.coerceIn(10L, 300L))
            .putBoolean(KEY_AUTO_PLAY, settings.autoPlayLiveEvents)
            .putBoolean(KEY_INTERRUPT_VOD, settings.interruptVodWhenLive)
            .putBoolean(KEY_RETURN_HOME, settings.returnHomeAfterEvent)
            .putBoolean(KEY_LAUNCH_ON_BOOT, settings.launchOnBoot)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepScreenAwake)
            .putBoolean(KEY_EXTREME_KIOSK, settings.extremeKioskMode)
            .also { if (organizationChanged) it.remove(KEY_ORGANIZATION_NAME) }
            .apply()
    }

    fun saveOrganizationName(name: String?) {
        preferences.edit().apply {
            if (name.isNullOrBlank()) remove(KEY_ORGANIZATION_NAME)
            else putString(KEY_ORGANIZATION_NAME, name)
        }.apply()
    }

    fun loadOrganizationName(): String? =
        preferences.getString(KEY_ORGANIZATION_NAME, null)?.takeIf { it.isNotBlank() }

    fun isFirstRunSetupRequired(): Boolean =
        !preferences.getBoolean(KEY_FIRST_RUN_COMPLETE, false) && load().organizationInput.isBlank()

    fun markFirstRunSetupComplete() {
        preferences.edit().putBoolean(KEY_FIRST_RUN_COMPLETE, true).apply()
    }

    fun setExtremeKioskMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_EXTREME_KIOSK, enabled).apply()
    }

    fun temporarilyUnlockKiosk(durationMs: Long = TEMPORARY_UNLOCK_DURATION_MS) {
        preferences.edit()
            .putLong(KEY_KIOSK_UNLOCK_UNTIL, System.currentTimeMillis() + durationMs.coerceAtLeast(0L))
            .apply()
    }

    fun clearTemporaryKioskUnlock() {
        preferences.edit().remove(KEY_KIOSK_UNLOCK_UNTIL).apply()
    }

    fun pauseLiveAutoPlay() {
        preferences.edit()
            .putBoolean(KEY_LIVE_AUTO_PLAY_PAUSED, true)
            .remove(KEY_SUPPRESSED_LIVE_EVENT)
            .apply()
    }

    fun isLiveAutoPlayPaused(): Boolean =
        preferences.getBoolean(KEY_LIVE_AUTO_PLAY_PAUSED, false)

    fun resumeLiveAutoPlay() {
        preferences.edit()
            .remove(KEY_LIVE_AUTO_PLAY_PAUSED)
            .remove(KEY_SUPPRESSED_LIVE_EVENT)
            .apply()
    }

    fun kioskUnlockUntilEpochMs(): Long? = preferences
        .getLong(KEY_KIOSK_UNLOCK_UNTIL, 0L)
        .takeIf { it > System.currentTimeMillis() }

    fun isExtremeKioskEnforced(): Boolean =
        load().extremeKioskMode && kioskUnlockUntilEpochMs() == null

    fun hasAdminPin(): Boolean = preferences.contains(KEY_PIN_HASH)

    fun setAdminPin(pin: String) {
        require(pin.length >= 4) { "PIN must contain at least four digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        preferences.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, hashPin(pin, salt))
            .apply()
    }

    fun verifyAdminPin(pin: String): Boolean {
        val salt = preferences.getString(KEY_PIN_SALT, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?: return false
        val expected = preferences.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            hashPin(pin, salt).toByteArray(Charsets.UTF_8)
        )
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.encodeToString(digest.digest(pin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private companion object {
        const val FILE_NAME = "hudl_fan_tv_settings"
        const val KEY_ORGANIZATION = "organization_input"
        const val KEY_ORGANIZATION_NAME = "organization_name"
        const val KEY_FIRST_RUN_COMPLETE = "first_run_setup_complete"
        const val KEY_REFRESH_INTERVAL = "refresh_interval_seconds"
        const val KEY_AUTO_PLAY = "auto_play_live_events"
        const val KEY_INTERRUPT_VOD = "interrupt_vod_when_live"
        const val KEY_RETURN_HOME = "return_home_after_event"
        const val KEY_LAUNCH_ON_BOOT = "launch_on_boot"
        const val KEY_KEEP_AWAKE = "keep_screen_awake"
        const val KEY_EXTREME_KIOSK = "extreme_kiosk_mode"
        const val KEY_KIOSK_UNLOCK_UNTIL = "kiosk_unlock_until_epoch_ms"
        const val KEY_LIVE_AUTO_PLAY_PAUSED = "live_auto_play_paused"
        // Removed after 0.3.1; retained only so upgrades can clean up the old preference.
        const val KEY_SUPPRESSED_LIVE_EVENT = "suppressed_live_auto_play_event_id"
        const val KEY_PIN_SALT = "admin_pin_salt"
        const val KEY_PIN_HASH = "admin_pin_hash"
        const val TEMPORARY_UNLOCK_DURATION_MS = 5 * 60 * 1_000L
    }
}
