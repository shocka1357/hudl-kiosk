package tv.hudl.fanplayer

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tv.hudl.fanplayer.automation.EventPollingCoordinator
import tv.hudl.fanplayer.data.EventCacheStore
import tv.hudl.fanplayer.data.HudlPublicGraphQlService
import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.HudlEventStatus
import tv.hudl.fanplayer.domain.OrganizationReference
import tv.hudl.fanplayer.player.HudlWebViewPlayerActivity
import tv.hudl.fanplayer.settings.AdminSettingsActivity
import tv.hudl.fanplayer.settings.AppSettings
import tv.hudl.fanplayer.settings.SettingsStore
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val service = HudlPublicGraphQlService()
    private val settingsStore by lazy { SettingsStore(this) }
    private val eventCache by lazy { EventCacheStore(this) }
    private val thumbnailExecutor = Executors.newFixedThreadPool(3)

    private lateinit var title: TextView
    private lateinit var organizationName: TextView
    private lateinit var organizationId: TextView
    private lateinit var status: TextView
    private lateinit var liveCard: View
    private lateinit var liveNow: TextView
    private lateinit var nextCard: View
    private lateinit var nextStream: TextView
    private lateinit var nextThumbnail: ImageView
    private lateinit var vodLibrary: LinearLayout

    private var latestEvents: List<HudlEvent> = emptyList()
    private var lastAutoPlayedEventId: String? = null
    private var bootOpenPending = false

    private val pollingCoordinator by lazy {
        EventPollingCoordinator(service) { result ->
            runOnUiThread { renderPollingResult(result) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bootOpenPending = intent.getBooleanExtra(EXTRA_OPEN_NEXT_STREAM, false)

        title = findViewById(R.id.title)
        organizationName = findViewById(R.id.organization_name)
        organizationId = findViewById(R.id.organization_id)
        status = findViewById(R.id.status)
        liveCard = findViewById(R.id.live_card)
        liveNow = findViewById(R.id.live_now)
        nextCard = findViewById(R.id.next_card)
        nextStream = findViewById(R.id.next_stream)
        nextThumbnail = findViewById(R.id.next_thumbnail)
        vodLibrary = findViewById(R.id.previous_streams)

        title.isFocusable = true
        title.isLongClickable = true
        title.setOnLongClickListener {
            openAdminSettings()
            true
        }

        eventCache.load()?.takeIf { it.events.isNotEmpty() }?.let { cached ->
            renderDashboard(cached.events)
            status.text = "Checking Hudl • cached schedule ready"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bootOpenPending = intent.getBooleanExtra(EXTRA_OPEN_NEXT_STREAM, false)
    }

    override fun onStart() {
        super.onStart()
        val settings = settingsStore.load()
        applyDeviceSettings(settings)
        val organization = OrganizationReference.parse(settings.organizationInput)
        if (organization == null) {
            organizationName.text = "Setup required"
            organizationId.text = "Hold OK on HUDL KIOSK to configure"
            status.text = "No organization configured"
            title.requestFocus()
        } else {
            organizationId.text = "HUDL ORGANIZATION ${organization.id}"
            startMonitoring(organization, settings)
        }
    }

    override fun onStop() {
        pollingCoordinator.stop()
        super.onStop()
    }

    override fun onDestroy() {
        pollingCoordinator.close()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun openAdminSettings() {
        startActivity(Intent(this, AdminSettingsActivity::class.java))
    }

    private fun applyDeviceSettings(settings: AppSettings) {
        if (settings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startMonitoring(organization: OrganizationReference, settings: AppSettings) {
        status.text = "Checking live schedule…"
        pollingCoordinator.start(organization, settings.refreshIntervalSeconds)
    }

    private fun renderPollingResult(result: Result<List<HudlEvent>>) {
        result.onSuccess { items ->
            latestEvents = items
            eventCache.save(items)
            renderDashboard(items)
            status.text = "LIVE MONITORING • updates every ${settingsStore.load().refreshIntervalSeconds}s"

            if (bootOpenPending) {
                maybeOpenBootTarget(items)
            } else {
                maybeAutoPlayLiveEvent(items)
            }
        }.onFailure { error ->
            status.text = if (latestEvents.isEmpty()) {
                error.message ?: "Hudl is unavailable • retrying automatically"
            } else {
                "OFFLINE • showing saved schedule • retrying automatically"
            }
        }
    }

    private fun renderDashboard(items: List<HudlEvent>) {
        latestEvents = items
        items.firstNotNullOfOrNull { it.organizationName }?.let { organizationName.text = it }
        renderLive(items)
        renderNext(items)
        renderVodLibrary(items)
    }

    private fun renderLive(items: List<HudlEvent>) {
        val live = items.firstOrNull { it.status == HudlEventStatus.LIVE }
        if (live == null) {
            liveNow.text = "Nothing live right now\nWe’ll switch automatically when the next broadcast begins."
            liveCard.isFocusable = false
            liveCard.setOnClickListener(null)
        } else {
            liveNow.text = "${live.title}\n${displayTime(live.startTimeUtc)} • LIVE NOW"
            liveCard.isFocusable = live.playbackPageUrl != null
            liveCard.setOnClickListener { openEvent(live) }
        }
    }

    private fun renderNext(items: List<HudlEvent>) {
        val next = items
            .filter { it.status == HudlEventStatus.UPCOMING }
            .minByOrNull { it.startTimeUtc ?: "9999" }

        nextThumbnail.setImageDrawable(null)
        if (next == null) {
            nextStream.text = "Schedule not announced\nThe kiosk will keep checking Hudl."
            nextCard.isFocusable = false
            nextCard.setOnClickListener(null)
            return
        }

        nextStream.text = "${next.title}\n${displayTime(next.startTimeUtc)}"
        next.thumbnailUrl?.let { loadThumbnail(it, nextThumbnail) }
        nextCard.isFocusable = next.playbackPageUrl != null
        nextCard.setOnClickListener { openEvent(next) }
        if (nextCard.isFocusable && title.hasFocus()) nextCard.requestFocus()
    }

    private fun renderVodLibrary(items: List<HudlEvent>) {
        vodLibrary.removeAllViews()
        val vods = items.filter {
            it.status == HudlEventStatus.ENDED && it.playbackPageUrl != null
        }
        if (vods.isEmpty()) {
            vodLibrary.addView(TextView(this).apply {
                text = "No on-demand broadcasts are available yet."
                setTextColor(Color.rgb(177, 187, 197))
                textSize = 18f
                setPadding(0, dp(18), 0, dp(32))
            })
            return
        }

        vods.take(24).forEach { event -> vodLibrary.addView(createVodCard(event)) }
    }

    private fun createVodCard(event: HudlEvent): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.bg_focusable_card)
        setPadding(dp(16), dp(16), dp(20), dp(16))
        setOnClickListener { openEvent(event) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(14))
        }

        val thumbnail = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(141))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(24, 31, 39))
            contentDescription = "Thumbnail for ${event.title}"
        }
        event.thumbnailUrl?.let { loadThumbnail(it, thumbnail) }
        addView(thumbnail)

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(dp(24), 0, 0, 0)
            }
            addView(TextView(context).apply {
                text = event.title
                setTextColor(Color.WHITE)
                textSize = 21f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = displayTime(event.startTimeUtc)
                setTextColor(Color.rgb(177, 187, 197))
                textSize = 16f
                setPadding(0, dp(7), 0, dp(11))
            })
            addView(TextView(context).apply {
                text = "▶  PLAY ON DEMAND"
                setTextColor(Color.rgb(151, 216, 77))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        })
    }

    private fun maybeOpenBootTarget(items: List<HudlEvent>) {
        val target = items.firstOrNull {
            it.status == HudlEventStatus.LIVE && it.playbackPageUrl != null
        } ?: items
            .filter { it.status == HudlEventStatus.UPCOMING && it.playbackPageUrl != null }
            .minByOrNull { it.startTimeUtc ?: "9999" }

        if (target != null) {
            bootOpenPending = false
            lastAutoPlayedEventId = target.id
            openEvent(target)
        }
    }

    private fun maybeAutoPlayLiveEvent(items: List<HudlEvent>) {
        if (!settingsStore.load().autoPlayLiveEvents) return
        val live = items.firstOrNull {
            it.status == HudlEventStatus.LIVE &&
                it.playbackPageUrl != null &&
                it.id != lastAutoPlayedEventId
        } ?: return
        lastAutoPlayedEventId = live.id
        openEvent(live)
    }

    private fun openEvent(event: HudlEvent) {
        event.playbackPageUrl?.let { url ->
            startActivity(Intent(this, HudlWebViewPlayerActivity::class.java)
                .putExtra(HudlWebViewPlayerActivity.EXTRA_URL, url)
                .putExtra(HudlWebViewPlayerActivity.EXTRA_EVENT_ID, event.id)
                .putExtra(HudlWebViewPlayerActivity.EXTRA_EVENT_TITLE, event.title)
                .putExtra(
                    HudlWebViewPlayerActivity.EXTRA_IS_LIVE,
                    event.status == HudlEventStatus.LIVE
                ))
        }
    }

    private fun loadThumbnail(url: String, target: ImageView) {
        target.tag = url
        thumbnailExecutor.execute {
            val bitmap = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "HudlKiosk/0.2")
                }
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (target.tag == url) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun displayTime(value: String?): String {
        if (value.isNullOrBlank()) return "Time to be announced"
        val inputPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        val parsed = inputPatterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)
            }.getOrNull()
        } ?: return value.replace("T", "  •  ").removeSuffix("Z")
        return SimpleDateFormat("EEE, MMM d  •  h:mm a", Locale.getDefault()).format(parsed)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_OPEN_NEXT_STREAM = "open_next_stream_after_boot"
    }
}
