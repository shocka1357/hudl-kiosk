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
        keepScreenAwake = preferences.getBoolean(KEY_KEEP_AWAKE, true)
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_ORGANIZATION, settings.organizationInput)
            .putLong(KEY_REFRESH_INTERVAL, settings.refreshIntervalSeconds.coerceIn(10L, 300L))
            .putBoolean(KEY_AUTO_PLAY, settings.autoPlayLiveEvents)
            .putBoolean(KEY_INTERRUPT_VOD, settings.interruptVodWhenLive)
            .putBoolean(KEY_RETURN_HOME, settings.returnHomeAfterEvent)
            .putBoolean(KEY_LAUNCH_ON_BOOT, settings.launchOnBoot)
            .putBoolean(KEY_KEEP_AWAKE, settings.keepScreenAwake)
            .apply()
    }

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
        const val KEY_REFRESH_INTERVAL = "refresh_interval_seconds"
        const val KEY_AUTO_PLAY = "auto_play_live_events"
        const val KEY_INTERRUPT_VOD = "interrupt_vod_when_live"
        const val KEY_RETURN_HOME = "return_home_after_event"
        const val KEY_LAUNCH_ON_BOOT = "launch_on_boot"
        const val KEY_KEEP_AWAKE = "keep_screen_awake"
        const val KEY_PIN_SALT = "admin_pin_salt"
        const val KEY_PIN_HASH = "admin_pin_hash"
    }
}
