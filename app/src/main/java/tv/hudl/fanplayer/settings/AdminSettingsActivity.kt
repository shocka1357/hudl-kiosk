package tv.hudl.fanplayer.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import tv.hudl.fanplayer.MainActivity
import tv.hudl.fanplayer.R
import tv.hudl.fanplayer.domain.OrganizationReference
import tv.hudl.fanplayer.kiosk.KioskAccessibilityService

class AdminSettingsActivity : AppCompatActivity() {
    private val store by lazy { SettingsStore(this) }
    private lateinit var organization: EditText
    private lateinit var interval: EditText
    private lateinit var autoPlay: SwitchCompat
    private lateinit var interruptVod: SwitchCompat
    private lateinit var returnHome: SwitchCompat
    private lateinit var keepAwake: SwitchCompat
    private lateinit var extremeKiosk: SwitchCompat
    private lateinit var lockdownStatus: TextView
    private lateinit var newPin: EditText
    private lateinit var confirmPin: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_settings)

        organization = findViewById(R.id.admin_organization)
        interval = findViewById(R.id.admin_interval)
        autoPlay = findViewById(R.id.admin_auto_play)
        interruptVod = findViewById(R.id.admin_interrupt_vod)
        returnHome = findViewById(R.id.admin_return_home)
        keepAwake = findViewById(R.id.admin_keep_awake)
        extremeKiosk = findViewById(R.id.admin_extreme_kiosk)
        lockdownStatus = findViewById(R.id.admin_lockdown_status)
        newPin = findViewById(R.id.admin_new_pin)
        confirmPin = findViewById(R.id.admin_confirm_pin)

        if (store.hasAdminPin()) {
            findViewById<android.view.View>(R.id.admin_content).visibility = android.view.View.INVISIBLE
            requestExistingPin()
        } else {
            populateSettings()
        }

        findViewById<Button>(R.id.admin_save).setOnClickListener { save() }
        findViewById<Button>(R.id.admin_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.admin_accessibility_settings).setOnClickListener {
            store.setExtremeKioskMode(extremeKiosk.isChecked)
            if (extremeKiosk.isChecked) {
                store.temporarilyUnlockKiosk()
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.admin_lock_now).setOnClickListener {
            extremeKiosk.isChecked = true
            store.setExtremeKioskMode(true)
            store.clearTemporaryKioskUnlock()
            Toast.makeText(this, "Extreme kiosk lockdown enabled", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::lockdownStatus.isInitialized) updateLockdownStatus()
    }

    private fun requestExistingPin() {
        val pinInput = EditText(this).apply {
            hint = "Admin PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unlock Hudl Kiosk settings")
            .setView(pinInput)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Unlock", null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (store.verifyAdminPin(pinInput.text.toString())) {
                    dialog.dismiss()
                    findViewById<android.view.View>(R.id.admin_content).visibility = android.view.View.VISIBLE
                    populateSettings()
                } else {
                    pinInput.error = "Incorrect PIN"
                }
            }
        }
        dialog.show()
    }

    private fun populateSettings() {
        val settings = store.load()
        organization.setText(settings.organizationInput)
        interval.setText(settings.refreshIntervalSeconds.toString())
        autoPlay.isChecked = settings.autoPlayLiveEvents
        interruptVod.isChecked = settings.interruptVodWhenLive
        returnHome.isChecked = settings.returnHomeAfterEvent
        keepAwake.isChecked = settings.keepScreenAwake
        extremeKiosk.isChecked = settings.extremeKioskMode
        updateLockdownStatus()
    }

    private fun updateLockdownStatus() {
        val configured = extremeKiosk.isChecked || store.load().extremeKioskMode
        val serviceEnabled = KioskAccessibilityService.isEnabled(this)
        val unlockUntil = store.kioskUnlockUntilEpochMs()
        lockdownStatus.text = when {
            unlockUntil != null -> {
                val remainingMinutes = ((unlockUntil - System.currentTimeMillis()) / 60_000L + 1L)
                    .coerceAtLeast(1L)
                "Maintenance window open for about $remainingMinutes more minute(s). Press Lock now when finished."
            }
            configured && serviceEnabled ->
                "Lockdown active. Leaving Hudl Kiosk will return this device to the app."
            configured ->
                "Lockdown configured, but the Hudl Kiosk accessibility service still needs to be enabled."
            serviceEnabled ->
                "Accessibility service enabled; enforcement is currently turned off."
            else ->
                "Off. Enable the switch, save, then enable Hudl Kiosk under Accessibility settings."
        }
    }

    private fun save() {
        val organizationInput = organization.text.toString().trim()
        if (OrganizationReference.parse(organizationInput) == null) {
            organization.error = "Enter a Hudl organization URL or numeric ID"
            return
        }
        val intervalSeconds = interval.text.toString().toLongOrNull()
        if (intervalSeconds == null || intervalSeconds !in 10L..300L) {
            interval.error = "Use 10–300 seconds"
            return
        }
        val pin = newPin.text.toString()
        if (pin.isNotEmpty()) {
            if (!pin.matches(Regex("\\d{4,12}"))) {
                newPin.error = "Use 4–12 digits"
                return
            }
            if (pin != confirmPin.text.toString()) {
                confirmPin.error = "PINs do not match"
                return
            }
        }

        val current = store.load()
        store.save(current.copy(
            organizationInput = organizationInput,
            refreshIntervalSeconds = intervalSeconds,
            autoPlayLiveEvents = autoPlay.isChecked,
            interruptVodWhenLive = interruptVod.isChecked,
            returnHomeAfterEvent = returnHome.isChecked,
            launchOnBoot = true,
            keepScreenAwake = keepAwake.isChecked,
            extremeKioskMode = extremeKiosk.isChecked
        ))
        if (pin.isNotEmpty()) store.setAdminPin(pin)
        store.markFirstRunSetupComplete()
        Toast.makeText(this, "Hudl Kiosk settings saved", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
