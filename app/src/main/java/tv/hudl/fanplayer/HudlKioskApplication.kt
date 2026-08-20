package tv.hudl.fanplayer

import android.app.Application
import tv.hudl.fanplayer.management.ManagementServer

class HudlKioskApplication : Application() {
    private lateinit var managementServer: ManagementServer

    override fun onCreate() {
        super.onCreate()
        managementServer = ManagementServer(this, MANAGEMENT_PORT).also { it.start() }
    }

    companion object {
        const val MANAGEMENT_PORT = 8787
    }
}
