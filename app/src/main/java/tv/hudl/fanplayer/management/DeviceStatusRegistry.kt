package tv.hudl.fanplayer.management

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** In-process health signals shared by the kiosk UI, player, and local management server. */
object DeviceStatusRegistry {
    data class PlaybackSnapshot(
        val sessionId: String? = null,
        val state: String = "idle",
        val eventId: String? = null,
        val eventTitle: String? = null,
        val isLive: Boolean = false,
        val openedAtEpochMs: Long? = null,
        val playbackStartedAtEpochMs: Long? = null,
        val error: String? = null
    ) {
        val isPlaying: Boolean get() = state == "playing"
    }

    @Volatile
    private var playback = PlaybackSnapshot()
    private val lastPollSuccess = AtomicLong(0L)
    private val lastPollFailure = AtomicLong(0L)
    private val lastPollError = AtomicReference<String?>(null)

    @Synchronized
    fun playerOpened(sessionId: String, eventId: String?, eventTitle: String?, isLive: Boolean) {
        playback = PlaybackSnapshot(
            sessionId = sessionId,
            state = "starting",
            eventId = eventId,
            eventTitle = eventTitle,
            isLive = isLive,
            openedAtEpochMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun playerStarted(sessionId: String) {
        val current = playback
        if (current.sessionId != sessionId) return
        playback = current.copy(
            state = "playing",
            playbackStartedAtEpochMs = current.playbackStartedAtEpochMs
                ?: System.currentTimeMillis(),
            error = null
        )
    }

    @Synchronized
    fun playerFailed(sessionId: String, message: String?) {
        val current = playback
        if (current.sessionId != sessionId) return
        playback = current.copy(state = "error", error = message?.take(180))
    }

    @Synchronized
    fun playerClosed(sessionId: String) {
        val current = playback
        if (current.sessionId != sessionId) return
        playback = PlaybackSnapshot()
    }

    fun recordPollSuccess() {
        lastPollSuccess.set(System.currentTimeMillis())
        lastPollError.set(null)
    }

    fun recordPollFailure(message: String?) {
        lastPollFailure.set(System.currentTimeMillis())
        lastPollError.set(message?.take(180))
    }

    fun playbackSnapshot(): PlaybackSnapshot = playback
    fun lastPollSuccessEpochMs(): Long? = lastPollSuccess.get().takeIf { it > 0L }
    fun lastPollFailureEpochMs(): Long? = lastPollFailure.get().takeIf { it > 0L }
    fun lastPollError(): String? = lastPollError.get()
}
