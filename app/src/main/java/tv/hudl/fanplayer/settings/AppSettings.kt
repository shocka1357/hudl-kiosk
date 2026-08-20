package tv.hudl.fanplayer.settings

data class AppSettings(
    val organizationInput: String = "",
    val refreshIntervalSeconds: Long = 20L,
    val autoPlayLiveEvents: Boolean = true,
    val interruptVodWhenLive: Boolean = true,
    val returnHomeAfterEvent: Boolean = true,
    val launchOnBoot: Boolean = true,
    val keepScreenAwake: Boolean = true
)
