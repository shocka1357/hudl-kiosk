package tv.hudl.fanplayer.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tv.hudl.fanplayer.MainActivity
import tv.hudl.fanplayer.domain.OrganizationReference
import tv.hudl.fanplayer.settings.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore(context).load()
        if (!settings.launchOnBoot || OrganizationReference.parse(settings.organizationInput) == null) return
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_NEXT_STREAM, true)
        })
    }
}
