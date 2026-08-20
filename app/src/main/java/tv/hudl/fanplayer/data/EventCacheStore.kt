package tv.hudl.fanplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.HudlEventStatus

/** Small local snapshot used to render the dashboard while the network is unavailable. */
class EventCacheStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun save(events: List<HudlEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject()
                .put("id", event.id)
                .put("broadcastId", event.broadcastId)
                .put("organizationName", event.organizationName)
                .put("title", event.title)
                .put("sport", event.sport)
                .put("startTimeUtc", event.startTimeUtc)
                .put("status", event.status.name)
                .put("thumbnailUrl", event.thumbnailUrl)
                .put("playbackPageUrl", event.playbackPageUrl))
        }
        preferences.edit()
            .putString(KEY_EVENTS, array.toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): CachedEvents? = runCatching {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return null
        val array = JSONArray(raw)
        val events = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(HudlEvent(
                    id = item.optString("id"),
                    broadcastId = item.nullableString("broadcastId"),
                    organizationName = item.nullableString("organizationName"),
                    title = item.optString("title", "Untitled Hudl event"),
                    sport = item.nullableString("sport"),
                    startTimeUtc = item.nullableString("startTimeUtc"),
                    status = runCatching { HudlEventStatus.valueOf(item.optString("status")) }
                        .getOrDefault(HudlEventStatus.UNKNOWN),
                    thumbnailUrl = item.nullableString("thumbnailUrl"),
                    playbackPageUrl = item.nullableString("playbackPageUrl")
                ))
            }
        }
        CachedEvents(events, preferences.getLong(KEY_SAVED_AT, 0L))
    }.getOrNull()

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    data class CachedEvents(val events: List<HudlEvent>, val savedAtEpochMs: Long)

    private companion object {
        const val FILE_NAME = "hudl_event_cache"
        const val KEY_EVENTS = "events_json"
        const val KEY_SAVED_AT = "saved_at_epoch_ms"
    }
}
