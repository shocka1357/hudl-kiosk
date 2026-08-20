package tv.hudl.fanplayer.automation

import tv.hudl.fanplayer.data.HudlEventService
import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.OrganizationReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Single-threaded, non-overlapping event polling with bounded retry backoff.
 * The caller owns the lifecycle and must call close when the screen is destroyed.
 */
class EventPollingCoordinator(
    private val service: HudlEventService,
    private val onResult: (Result<List<HudlEvent>>) -> Unit
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hudl-event-poller").apply { isDaemon = true }
    }

    @Volatile
    private var generation = 0L
    private var scheduledPoll: ScheduledFuture<*>? = null

    @Synchronized
    fun start(organization: OrganizationReference, intervalSeconds: Long) {
        stop()
        val token = ++generation
        val normalDelayMs = intervalSeconds.coerceIn(10L, 300L) * 1_000L
        schedule(token, organization, 0L, normalDelayMs)
    }

    @Synchronized
    fun stop() {
        generation++
        scheduledPoll?.cancel(false)
        scheduledPoll = null
    }

    fun close() {
        stop()
        scheduler.shutdownNow()
    }

    private fun schedule(
        token: Long,
        organization: OrganizationReference,
        delayMs: Long,
        normalDelayMs: Long
    ) {
        scheduledPoll = scheduler.schedule({
            if (token == generation) {
                val result = runCatching { service.fetchEvents(organization) }
                onResult(result)

                val nextDelayMs = if (result.isSuccess) {
                    normalDelayMs
                } else {
                    min(maxOf(delayMs, MIN_BACKOFF_MS) * 2L, MAX_BACKOFF_MS)
                }
                if (token == generation) {
                    schedule(token, organization, nextDelayMs, normalDelayMs)
                }
            }
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5 * 60 * 1_000L
    }
}
