package tv.hudl.fanplayer.domain

data class HudlEvent(
    val id: String,
    val broadcastId: String?,
    val organizationName: String?,
    val title: String,
    val sport: String?,
    val startTimeUtc: String?,
    val status: HudlEventStatus,
    val thumbnailUrl: String?,
    /** Hudl's public embed page; not yet a native HLS/DASH manifest. */
    val playbackPageUrl: String?
)

enum class HudlEventStatus(val label: String) {
    UPCOMING("Upcoming"),
    LIVE("Live"),
    ENDED("Ended"),
    UNKNOWN("Status unavailable");

    companion object {
        fun fromHudlStatus(value: String): HudlEventStatus = when (value.trim().lowercase()) {
            "live", "streaming", "in progress", "in_progress" -> LIVE
            "upcoming", "scheduled" -> UPCOMING
            "archived", "ended", "completed" -> ENDED
            else -> UNKNOWN
        }
    }
}
