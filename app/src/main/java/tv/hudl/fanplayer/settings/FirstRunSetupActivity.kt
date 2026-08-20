package tv.hudl.fanplayer.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import tv.hudl.fanplayer.MainActivity
import tv.hudl.fanplayer.R
import tv.hudl.fanplayer.domain.OrganizationReference

/** One-time, local-only setup shown before an unconfigured kiosk can start. */
class FirstRunSetupActivity : AppCompatActivity() {
    private val store by lazy { SettingsStore(this) }

    private lateinit var organization: EditText
    private lateinit var pin: EditText
    private lateinit var confirmPin: EditText
    private lateinit var interval: EditText
    private lateinit var autoPlay: SwitchCompat
    private lateinit var interruptVod: SwitchCompat
    private lateinit var returnHome: SwitchCompat
    private lateinit var keepAwake: SwitchCompat
    private lateinit var extremeKiosk: SwitchCompat
    private lateinit var finishSetup: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_first_run_setup)

        organization = findViewById(R.id.setup_organization)
        pin = findViewById(R.id.setup_pin)
        confirmPin = findViewById(R.id.setup_confirm_pin)
        interval = findViewById(R.id.setup_interval)
        autoPlay = findViewById(R.id.setup_auto_play)
        interruptVod = findViewById(R.id.setup_interrupt_vod)
        returnHome = findViewById(R.id.setup_return_home)
        keepAwake = findViewById(R.id.setup_keep_awake)
        extremeKiosk = findViewById(R.id.setup_extreme_kiosk)
        finishSetup = findViewById(R.id.setup_finish)

        pin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        confirmPin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        populateDefaults()
        finishSetup.setOnClickListener { saveAndStart() }
        organization.requestFocus()
    }

    private fun populateDefaults() {
        val settings = store.load()
        organization.setText(settings.organizationInput)
        interval.setText(settings.refreshIntervalSeconds.toString())
        autoPlay.isChecked = settings.autoPlayLiveEvents
        interruptVod.isChecked = settings.interruptVodWhenLive
        returnHome.isChecked = settings.returnHomeAfterEvent
        keepAwake.isChecked = settings.keepScreenAwake
        extremeKiosk.isChecked = settings.extremeKioskMode
    }

    private fun saveAndStart() {
        val organizationInput = organization.text.toString().trim()
        if (OrganizationReference.parse(organizationInput) == null) {
            organization.error = "Paste the full Hudl Fan organization URL or enter its numeric ID"
            organization.requestFocus()
            return
        }

        val enteredPin = pin.text.toString()
        if (!enteredPin.matches(Regex("\\d{4,12}"))) {
            pin.error = "Create a PIN containing 4–12 digits"
            pin.requestFocus()
            return
        }
        if (enteredPin != confirmPin.text.toString()) {
            confirmPin.error = "PINs do not match"
            confirmPin.requestFocus()
            return
        }

        val intervalSeconds = interval.text.toString().toLongOrNull()
        if (intervalSeconds == null || intervalSeconds !in 10L..300L) {
            interval.error = "Use a refresh time from 10–300 seconds"
            interval.requestFocus()
            return
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
        store.setAdminPin(enteredPin)
        store.markFirstRunSetupComplete()

        Toast.makeText(this, "Setup complete — starting Hudl Kiosk", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }
}
