package tv.hudl.fanplayer.player

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.hudl.fanplayer.automation.EventPollingCoordinator
import tv.hudl.fanplayer.data.HudlPublicGraphQlService
import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.HudlEventStatus
import tv.hudl.fanplayer.domain.OrganizationReference
import tv.hudl.fanplayer.settings.SettingsStore

/** Public Hudl embed fallback with autoplay and live-event takeover while watching VOD. */
class HudlWebViewPlayerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var switchPanel: LinearLayout
    private lateinit var switchMessage: TextView

    private val settingsStore by lazy { SettingsStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentEventId: String? = null
    private var currentEventTitle: String? = null
    private var currentIsLive = false
    private var suppressedLiveEventId: String? = null
    private var pendingLiveEvent: HudlEvent? = null
    private var countdownSeconds = SWITCH_COUNTDOWN_SECONDS

    private val livePollingCoordinator by lazy {
        EventPollingCoordinator(HudlPublicGraphQlService()) { result ->
            runOnUiThread { result.onSuccess(::handlePlaybackEvents) }
        }
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            val live = pendingLiveEvent ?: return
            if (countdownSeconds <= 0) {
                switchToLiveEvent(live)
                return
            }
            switchMessage.text = "${live.title} is live\nSwitching in $countdownSeconds seconds"
            countdownSeconds--
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        currentEventId = intent.getStringExtra(EXTRA_EVENT_ID)
        currentEventTitle = intent.getStringExtra(EXTRA_EVENT_TITLE)
        currentIsLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, 8, 14, 19))
            setPadding(36, 24, 36, 24)
            textSize = 19f
            gravity = Gravity.CENTER
            text = "Starting ${currentEventTitle ?: "Hudl broadcast"}…"
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    statusView.visibility = View.VISIBLE
                    statusView.text = "Starting ${currentEventTitle ?: "Hudl broadcast"}…"
                }

                override fun onPageFinished(view: WebView, url: String) {
                    statusView.visibility = View.GONE
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) = showError(description)

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) showError(error.description?.toString())
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = !isAllowedHudlUrl(request.url.toString())
            }
            webChromeClient = WebChromeClient()
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }

        switchMessage = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        switchPanel = createSwitchPanel()

        setContentView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addView(statusView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addView(Button(this@HudlWebViewPlayerActivity).apply {
                text = "Back to Hudl Kiosk"
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(16, 16, 16, 16)
            })
            addView(switchPanel, FrameLayout.LayoutParams(dp(680), -2, Gravity.CENTER))
        })

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || !isAllowedHudlUrl(url)) {
            showError("This is not a valid public Hudl playback URL.")
        } else {
            webView.loadUrl(withAutoplay(url))
        }
    }

    override fun onStart() {
        super.onStart()
        if (!currentIsLive && settingsStore.load().interruptVodWhenLive) {
            OrganizationReference.parse(settingsStore.load().organizationInput)?.let {
                livePollingCoordinator.start(it, LIVE_CHECK_INTERVAL_SECONDS)
            }
        }
    }

    override fun onStop() {
        livePollingCoordinator.stop()
        cancelCountdown(suppressEvent = false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        livePollingCoordinator.close()
        mainHandler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun createSwitchPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(38), dp(30), dp(38), dp(28))
        visibility = View.GONE
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(245, 11, 19, 25))
            setStroke(dp(3), Color.rgb(151, 216, 77))
        }
        addView(switchMessage, LinearLayout.LayoutParams(-1, -2))
        addView(Button(context).apply {
            text = "Keep watching this replay"
            isFocusable = true
            setOnClickListener { cancelCountdown(suppressEvent = true) }
        }, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(22)
        })
    }

    private fun handlePlaybackEvents(events: List<HudlEvent>) {
        if (currentIsLive || pendingLiveEvent != null) return
        val live = events.firstOrNull {
            it.status == HudlEventStatus.LIVE &&
                it.playbackPageUrl != null &&
                it.id != currentEventId &&
                it.id != suppressedLiveEventId
        } ?: return
        pendingLiveEvent = live
        countdownSeconds = SWITCH_COUNTDOWN_SECONDS
        switchPanel.visibility = View.VISIBLE
        switchPanel.requestFocus()
        mainHandler.post(countdownTick)
    }

    private fun cancelCountdown(suppressEvent: Boolean) {
        if (suppressEvent) suppressedLiveEventId = pendingLiveEvent?.id
        mainHandler.removeCallbacks(countdownTick)
        pendingLiveEvent = null
        switchPanel.visibility = View.GONE
        webView.requestFocus()
    }

    private fun switchToLiveEvent(event: HudlEvent) {
        mainHandler.removeCallbacks(countdownTick)
        pendingLiveEvent = null
        switchPanel.visibility = View.GONE
        currentEventId = event.id
        currentEventTitle = event.title
        currentIsLive = true
        livePollingCoordinator.stop()
        event.playbackPageUrl?.let { webView.loadUrl(withAutoplay(it)) }
    }

    private fun showError(description: CharSequence?) {
        statusView.visibility = View.VISIBLE
        statusView.text = description?.takeIf { it.isNotBlank() }?.let { "Hudl playback unavailable: $it" }
            ?: "Hudl playback unavailable."
    }

    private fun withAutoplay(value: String): String {
        val autoplay = Regex("([?&])autoplay=[^&]*", RegexOption.IGNORE_CASE)
        if (autoplay.containsMatchIn(value)) {
            return autoplay.replace(value) { "${it.groupValues[1]}autoplay=1" }
        }
        return value + if (value.contains('?')) "&autoplay=1" else "?autoplay=1"
    }

    private fun isAllowedHudlUrl(value: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(value)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "hudl.com" || host.endsWith(".hudl.com"))
    }.getOrDefault(false)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "hudl_embed_url"
        const val EXTRA_EVENT_ID = "hudl_event_id"
        const val EXTRA_EVENT_TITLE = "hudl_event_title"
        const val EXTRA_IS_LIVE = "hudl_event_is_live"
        private const val SWITCH_COUNTDOWN_SECONDS = 10
        private const val LIVE_CHECK_INTERVAL_SECONDS = 10L
    }
}
