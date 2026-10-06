package com.bondhu.cockpitdriver

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var phoneInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var cockpitIdInput: EditText
    private lateinit var cockpitPasswordInput: EditText
    private lateinit var ersPinInput: EditText
    private lateinit var rememberCheck: CheckBox
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadCredentials()
        refreshUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Cockpit UI Driver"
            textSize = 26f
            setTextColor(Color.rgb(20, 65, 95))
            gravity = Gravity.CENTER
        }
        root.addView(title, lp())

        val note = TextView(this).apply {
            text = "নিজের/অনুমোদিত Cockpit account-এর জন্য ব্যবহার করুন।"
            textSize = 14f
            setPadding(0, 10, 0, 18)
        }
        root.addView(note, lp())

        val permission = Button(this).apply {
            text = "১. Accessibility Permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(permission, lp())

        cockpitIdInput = field("Cockpit ID / POS", InputType.TYPE_CLASS_TEXT)
        root.addView(cockpitIdInput, lp())

        cockpitPasswordInput = passwordField("Cockpit Password")
        root.addView(cockpitPasswordInput, lp())

        ersPinInput = passwordField("ERS PIN")
        root.addView(ersPinInput, lp())

        rememberCheck = CheckBox(this).apply {
            text = "Credentials নিরাপদভাবে এই ডিভাইসে সংরক্ষণ করুন"
            isChecked = true
        }
        root.addView(rememberCheck, lp())

        phoneInput = field("GP মোবাইল নম্বর", InputType.TYPE_CLASS_PHONE)
        root.addView(phoneInput, lp())

        amountInput = field("রিচার্জ পরিমাণ (যেমন 20)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(amountInput, lp())

        runButton = Button(this).apply {
            text = "RUN RECHARGE"
            setOnClickListener { startDriver() }
        }
        root.addView(runButton, lp())

        stopButton = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                DriverSession.stop()
                refreshUi()
            }
        }
        root.addView(stopButton, lp())

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 18, 0, 0)
        }
        root.addView(status, lp())

        val warning = TextView(this).apply {
            text = "Security: PIN/Password encrypted storage-এ রাখা হবে। App uninstall/clear data করলে saved credentials মুছে যাবে।"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        root.addView(warning, lp())

        setContentView(root)
    }

    private fun field(hint: String, type: Int) =
        EditText(this).apply {
            this.hint = hint
            inputType = type
            textSize = 17f
        }

    private fun passwordField(hint: String) =
        EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 17f
        }

    private fun loadCredentials() {
        cockpitIdInput.setText(SecureStore.get(this, "cockpit_id"))
        cockpitPasswordInput.setText(SecureStore.get(this, "cockpit_password"))
        ersPinInput.setText(SecureStore.get(this, "ers_pin"))
    }

    private fun saveCredentials() {
        if (!rememberCheck.isChecked) {
            SecureStore.clear(this, "cockpit_id")
            SecureStore.clear(this, "cockpit_password")
            SecureStore.clear(this, "ers_pin")
            return
        }
        SecureStore.put(this, "cockpit_id", cockpitIdInput.text.toString())
        SecureStore.put(this, "cockpit_password", cockpitPasswordInput.text.toString())
        SecureStore.put(this, "ers_pin", ersPinInput.text.toString())
    }

    private fun startDriver() {
        val phone = phoneInput.text.toString().trim()
        val amount = amountInput.text.toString().trim()

        if (!phone.matches(Regex("01\\d{9}"))) {
            toast("সঠিক ১১ সংখ্যার GP নম্বর দিন")
            return
        }
        if (amount.isBlank() || amount.toDoubleOrNull() == null || amount.toDouble() <= 0) {
            toast("সঠিক রিচার্জ পরিমাণ দিন")
            return
        }
        if (!isAccessibilityEnabled()) {
            toast("আগে Accessibility Permission দিন")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        saveCredentials()
        DriverSession.start(phone, amount)

        val launch = findCockpitLaunchIntent()
        if (launch == null) {
            toast("Cockpit অ্যাপটি শনাক্ত করা যাচ্ছে না")
            DriverSession.stop("Cockpit launch activity পাওয়া যায়নি")
            refreshUi()
            return
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
        refreshUi()
    }

    private fun findCockpitLaunchIntent(): Intent? {
        val pm = packageManager
        // 1. Try known GP Cockpit packages first (most reliable)
        val knownPackages = listOf(
            "retail.grameenphone.com.gpretail",
            "com.grameenphone.cockpit"
        )
        for (pkg in knownPackages) {
            try {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) return launch
            } catch (_: Exception) { }
        }
        // 2. Fall back to label search
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        val cockpit = activities.firstOrNull { info ->
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            label.contains("Cockpit", ignoreCase = true) ||
                    label.contains("ককপিট", ignoreCase = true)
        }
        return cockpit?.let {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(it.activityInfo.packageName, it.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CockpitAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshUi() {
        status.text = "Status: ${DriverSession.state}\n${DriverSession.lastMessage}"
        runButton.isEnabled = !DriverSession.running
        stopButton.isEnabled = DriverSession.running
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 6, 0, 6) }
}
