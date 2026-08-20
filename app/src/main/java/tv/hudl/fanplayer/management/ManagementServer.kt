package tv.hudl.fanplayer.management

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import tv.hudl.fanplayer.data.EventCacheStore
import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.HudlEventStatus
import tv.hudl.fanplayer.domain.OrganizationReference
import tv.hudl.fanplayer.kiosk.KioskAccessibilityService
import tv.hudl.fanplayer.settings.SettingsStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Tiny dependency-free, read-only HTTP dashboard for local network management. */
class ManagementServer(
    context: Context,
    private val port: Int
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hudl-management-listener").apply { isDaemon = true }
    }
    private val clientExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "hudl-management-client").apply { isDaemon = true }
    }
    private val startedAtEpochMs = System.currentTimeMillis()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptExecutor.execute {
            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port), 8)
                }
            }.getOrElse {
                running.set(false)
                return@execute
            }

            server.use {
                while (running.get()) {
                    val client = runCatching { server.accept() }.getOrNull() ?: continue
                    clientExecutor.execute {
                        runCatching { handleClient(client) }
                            .onFailure { runCatching { client.close() } }
                    }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = CLIENT_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII), 2_048)
            val requestLine = reader.readLine()?.take(MAX_REQUEST_LINE_LENGTH) ?: return
            for (index in 0 until MAX_HEADER_LINES) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val request = requestLine.split(' ')
            if (request.size < 2) {
                respond(socket, 400, "text/plain; charset=utf-8", "Bad request")
                return
            }
            if (request[0] != "GET") {
                respond(socket, 405, "text/plain; charset=utf-8", "Read-only server: GET requests only")
                return
            }

            when (request[1].substringBefore('?')) {
                "/", "/index.html" -> respond(
                    socket,
                    200,
                    "text/html; charset=utf-8",
                    renderDashboard(buildStatus())
                )
                "/api/status", "/health" -> respond(
                    socket,
                    200,
                    "application/json; charset=utf-8",
                    buildStatus().toString(2)
                )
                else -> respond(socket, 404, "text/plain; charset=utf-8", "Not found")
            }
        }
    }

    private fun buildStatus(): JSONObject {
        val settingsStore = SettingsStore(appContext)
        val settings = settingsStore.load()
        val organization = OrganizationReference.parse(settings.organizationInput)
        val cached = EventCacheStore(appContext).load()
        val events = cached?.events.orEmpty()
        val playback = DeviceStatusRegistry.playbackSnapshot()
        val liveEvent = events.firstOrNull { it.status == HudlEventStatus.LIVE }
        val nextEvent = events
            .filter { it.status == HudlEventStatus.UPCOMING }
            .minByOrNull { it.startTimeUtc ?: "9999" }
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val accessibilityServiceEnabled = KioskAccessibilityService.isEnabled(appContext)
        val kioskUnlockUntil = settingsStore.kioskUnlockUntilEpochMs()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return JSONObject()
            .put("service", JSONObject()
                .put("name", "Hudl Kiosk local management")
                .put("mode", "read-only")
                .put("port", port)
                .put("addresses", JSONArray(localIpv4Addresses())))
            .put("app", JSONObject()
                .put("package", appContext.packageName)
                .put("versionName", packageInfo.versionName ?: "unknown")
                .put("versionCode", versionCode)
                .put("installedAt", isoTime(packageInfo.firstInstallTime))
                .put("lastUpdatedAt", isoTime(packageInfo.lastUpdateTime))
                .put("processStartedAt", isoTime(startedAtEpochMs))
                .put("processUptimeSeconds", (System.currentTimeMillis() - startedAtEpochMs) / 1_000L))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("product", Build.PRODUCT)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("apiLevel", Build.VERSION.SDK_INT)
                .put("deviceUptimeSeconds", SystemClock.elapsedRealtime() / 1_000L))
            .put("organization", JSONObject()
                .put("configured", organization != null)
                .putNullable("id", organization?.id)
                .putNullable("name", settingsStore.loadOrganizationName())
                .put("configuredInput", settings.organizationInput))
            .put("playback", JSONObject()
                .put("state", playback.state)
                .put("isPlaying", playback.isPlaying)
                .put("isLive", playback.isLive)
                .putNullable("eventId", playback.eventId)
                .putNullable("eventTitle", playback.eventTitle)
                .putNullable("playerOpenedAt", playback.openedAtEpochMs?.let(::isoTime))
                .putNullable("playbackStartedAt", playback.playbackStartedAtEpochMs?.let(::isoTime))
                .putNullable("error", playback.error))
            .put("settings", JSONObject()
                .put("refreshIntervalSeconds", settings.refreshIntervalSeconds)
                .put("autoPlayLiveEvents", settings.autoPlayLiveEvents)
                .put("interruptVodWhenLive", settings.interruptVodWhenLive)
                .put("returnHomeAfterEvent", settings.returnHomeAfterEvent)
                .put("launchOnBoot", settings.launchOnBoot)
                .put("keepScreenAwake", settings.keepScreenAwake)
                .put("extremeKioskMode", settings.extremeKioskMode)
                .put("adminPinConfigured", settingsStore.hasAdminPin()))
            .put("lockdown", JSONObject()
                .put("configured", settings.extremeKioskMode)
                .put("accessibilityServiceEnabled", accessibilityServiceEnabled)
                .put("enforcementActive", settings.extremeKioskMode &&
                    accessibilityServiceEnabled && kioskUnlockUntil == null)
                .put("temporarilyUnlocked", kioskUnlockUntil != null)
                .putNullable("maintenanceUnlockUntil", kioskUnlockUntil?.let(::isoTime))
                .put("implementation", "best-effort accessibility; not device-owner lock task"))
            .put("monitoring", JSONObject()
                .putNullable("lastPollSuccessAt", DeviceStatusRegistry.lastPollSuccessEpochMs()?.let(::isoTime))
                .putNullable("lastPollFailureAt", DeviceStatusRegistry.lastPollFailureEpochMs()?.let(::isoTime))
                .putNullable("lastPollError", DeviceStatusRegistry.lastPollError())
                .putNullable("eventCacheUpdatedAt", cached?.savedAtEpochMs?.let(::isoTime))
                .put("cachedEventCount", events.size)
                .put("liveEventCount", events.count { it.status == HudlEventStatus.LIVE })
                .put("upcomingEventCount", events.count { it.status == HudlEventStatus.UPCOMING })
                .put("vodEventCount", events.count { it.status == HudlEventStatus.ENDED })
                .putNullable("currentLive", liveEvent?.toStatusJson())
                .putNullable("nextUpcoming", nextEvent?.toStatusJson()))
            .put("generatedAt", isoTime(System.currentTimeMillis()))
    }

    private fun renderDashboard(status: JSONObject): String {
        val app = status.getJSONObject("app")
        val device = status.getJSONObject("device")
        val organization = status.getJSONObject("organization")
        val playback = status.getJSONObject("playback")
        val settings = status.getJSONObject("settings")
        val lockdown = status.getJSONObject("lockdown")
        val monitoring = status.getJSONObject("monitoring")
        val state = playback.getString("state")
        val stateClass = when (state) {
            "playing" -> "good"
            "starting" -> "warn"
            "error" -> "bad"
            else -> "muted"
        }
        val addresses = status.getJSONObject("service").getJSONArray("addresses")
        val addressText = (0 until addresses.length()).joinToString(" · ") {
            "http://${addresses.getString(it)}:$port"
        }.ifBlank { "Port $port · no LAN IPv4 address detected" }

        fun value(parent: JSONObject, key: String, fallback: String = "—"): String =
            parent.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: fallback
        fun yesNo(parent: JSONObject, key: String): String = if (parent.optBoolean(key)) "Yes" else "No"
        fun row(label: String, value: Any?): String =
            "<div class=\"row\"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value?.toString() ?: "—")}</strong></div>"

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <meta http-equiv="refresh" content="10">
              <title>Hudl Kiosk Management</title>
              <style>
                :root{color-scheme:dark;--bg:#071119;--card:#101d27;--line:#263744;--text:#f5f7f8;--muted:#a8b6c0;--lime:#97d84d;--amber:#ffbd59;--red:#ff6b6b}
                *{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top right,#173044 0,#071119 48%);color:var(--text);font:15px/1.45 system-ui,-apple-system,Segoe UI,sans-serif}
                main{max-width:1100px;margin:auto;padding:34px 22px 60px}.brand{display:flex;align-items:center;gap:14px;margin-bottom:8px}.logo{display:grid;place-items:center;width:48px;height:48px;border:3px solid var(--lime);border-radius:12px;color:var(--lime);font-size:22px}.eyebrow{color:var(--lime);font-weight:800;letter-spacing:.14em;font-size:12px}h1{font-size:30px;margin:2px 0}.address{color:var(--muted);word-break:break-all}.hero{margin:26px 0;display:flex;justify-content:space-between;align-items:center;gap:18px;padding:24px;border:1px solid var(--line);border-radius:18px;background:rgba(16,29,39,.88)}.state{padding:8px 13px;border-radius:999px;text-transform:uppercase;font-weight:900;letter-spacing:.08em}.state.good{background:rgba(151,216,77,.16);color:var(--lime)}.state.warn{background:rgba(255,189,89,.16);color:var(--amber)}.state.bad{background:rgba(255,107,107,.16);color:var(--red)}.state.muted{background:#22313b;color:var(--muted)}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:16px}.card{background:rgba(16,29,39,.92);border:1px solid var(--line);border-radius:16px;padding:20px}.card h2{font-size:14px;letter-spacing:.1em;text-transform:uppercase;color:var(--lime);margin:0 0 13px}.row{display:flex;justify-content:space-between;gap:18px;padding:9px 0;border-top:1px solid rgba(38,55,68,.7)}.row span{color:var(--muted)}.row strong{text-align:right;overflow-wrap:anywhere}.note{margin-top:20px;color:var(--muted);font-size:13px}.note a{color:var(--lime)}
              </style>
            </head>
            <body><main>
              <div class="brand"><div class="logo">▶</div><div><div class="eyebrow">LOCAL DEVICE STATUS</div><h1>Hudl Kiosk</h1></div></div>
              <div class="address">${escapeHtml(addressText)} · refreshes every 10 seconds</div>
              <section class="hero"><div><div class="eyebrow">NOW</div><h2>${escapeHtml(value(playback, "eventTitle", "No broadcast open"))}</h2><div class="address">${if (playback.optBoolean("isLive")) "Live broadcast" else "On-demand or idle"}</div></div><div class="state $stateClass">${escapeHtml(state)}</div></section>
              <div class="grid">
                <section class="card"><h2>Application</h2>
                  ${row("Version", "${value(app, "versionName")} (${app.optLong("versionCode")})")}
                  ${row("Package", value(app, "package"))}
                  ${row("Last updated", value(app, "lastUpdatedAt"))}
                  ${row("Process uptime", "${app.optLong("processUptimeSeconds")} sec")}
                </section>
                <section class="card"><h2>Organization</h2>
                  ${row("Name", value(organization, "name", "Resolving…"))}
                  ${row("Hudl ID", value(organization, "id"))}
                  ${row("Configured", yesNo(organization, "configured"))}
                  ${row("Input", value(organization, "configuredInput"))}
                </section>
                <section class="card"><h2>Playback</h2>
                  ${row("Playing", yesNo(playback, "isPlaying"))}
                  ${row("Live", yesNo(playback, "isLive"))}
                  ${row("State", state)}
                  ${row("Started", value(playback, "playbackStartedAt"))}
                </section>
                <section class="card"><h2>Monitoring</h2>
                  ${row("Last successful poll", value(monitoring, "lastPollSuccessAt"))}
                  ${row("Cached events", monitoring.optInt("cachedEventCount"))}
                  ${row("Live / upcoming / VOD", "${monitoring.optInt("liveEventCount")} / ${monitoring.optInt("upcomingEventCount")} / ${monitoring.optInt("vodEventCount")}")}
                  ${row("Cache updated", value(monitoring, "eventCacheUpdatedAt"))}
                  ${row("Last error", value(monitoring, "lastPollError", "None"))}
                </section>
                <section class="card"><h2>Kiosk Settings</h2>
                  ${row("Refresh interval", "${settings.optLong("refreshIntervalSeconds")} sec")}
                  ${row("Autoplay live", yesNo(settings, "autoPlayLiveEvents"))}
                  ${row("Interrupt VOD for live", yesNo(settings, "interruptVodWhenLive"))}
                  ${row("Return after event", yesNo(settings, "returnHomeAfterEvent"))}
                  ${row("Launch on boot", yesNo(settings, "launchOnBoot"))}
                  ${row("Keep screen awake", yesNo(settings, "keepScreenAwake"))}
                  ${row("Extreme kiosk configured", yesNo(lockdown, "configured"))}
                  ${row("Accessibility service", if (lockdown.optBoolean("accessibilityServiceEnabled")) "Enabled" else "Disabled")}
                  ${row("Lockdown enforcement", if (lockdown.optBoolean("enforcementActive")) "Active" else "Inactive")}
                  ${row("Maintenance unlock until", value(lockdown, "maintenanceUnlockUntil"))}
                  ${row("Admin PIN configured", yesNo(settings, "adminPinConfigured"))}
                </section>
                <section class="card"><h2>Device</h2>
                  ${row("Device", "${value(device, "manufacturer")} ${value(device, "model")}")}
                  ${row("Product", value(device, "product"))}
                  ${row("Android", "${value(device, "androidVersion")} · API ${device.optInt("apiLevel")}")}
                  ${row("Device uptime", "${device.optLong("deviceUptimeSeconds")} sec")}
                </section>
              </div>
              <div class="note">Read-only local service. It provides no controls and never exposes the admin PIN or PIN hash. JSON status: <a href="/api/status">/api/status</a>.</div>
            </main></body></html>
        """.trimIndent()
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().use { output ->
            output.write(headers)
            output.write(bytes)
            output.flush()
        }
    }

    private fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun HudlEvent.toStatusJson(): JSONObject = JSONObject()
        .put("eventId", id)
        .putNullable("broadcastId", broadcastId)
        .put("title", title)
        .put("status", status.name.lowercase(Locale.US))
        .putNullable("startTimeUtc", startTimeUtc)

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun isoTime(epochMs: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ssZ",
        Locale.US
    ).apply { timeZone = TimeZone.getDefault() }.format(Date(epochMs))

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private companion object {
        const val CLIENT_TIMEOUT_MS = 3_000
        const val MAX_REQUEST_LINE_LENGTH = 4_096
        const val MAX_HEADER_LINES = 64
    }
}
