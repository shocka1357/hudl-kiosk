package tv.hudl.fanplayer.settings

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import tv.hudl.fanplayer.R
import tv.hudl.fanplayer.domain.OrganizationReference

class AdminSettingsActivity : AppCompatActivity() {
    private val store by lazy { SettingsStore(this) }
    private lateinit var organization: EditText
    private lateinit var interval: EditText
    private lateinit var autoPlay: SwitchCompat
    private lateinit var keepAwake: SwitchCompat
    private lateinit var newPin: EditText
    private lateinit var confirmPin: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_settings)

        organization = findViewById(R.id.admin_organization)
        interval = findViewById(R.id.admin_interval)
        autoPlay = findViewById(R.id.admin_auto_play)
        keepAwake = findViewById(R.id.admin_keep_awake)
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
        keepAwake.isChecked = settings.keepScreenAwake
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
            launchOnBoot = true,
            keepScreenAwake = keepAwake.isChecked
        ))
        if (pin.isNotEmpty()) store.setAdminPin(pin)
        Toast.makeText(this, "Hudl Kiosk settings saved", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
