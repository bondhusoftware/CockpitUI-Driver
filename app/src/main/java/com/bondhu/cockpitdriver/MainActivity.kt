package com.bondhu.cockpitdriver

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var phoneInput: EditText
    private lateinit var amountInput: EditText
    private lateinit var bulkInput: EditText
    private lateinit var cockpitIdInput: EditText
    private lateinit var cockpitPasswordInput: EditText
    private lateinit var ersPinInput: EditText
    private lateinit var rememberCheck: CheckBox
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    // v19: নিচে লগ — নম্বরের পাশে স্ট্যাটাস, ক্লিকে বিস্তারিত
    private lateinit var logList: LinearLayout

    // v15: বাল্ক চলাকালীন লাইভ স্ট্যাটাস দেখানোর জন্য
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefresh = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 2000)
        }
    }


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

        val bulkLabel = TextView(this).apply {
            text = "বাল্ক লিস্ট (প্রতি লাইনে: নম্বর টাকা) — খালি থাকলে উপরের একটাই চলবে"
            textSize = 14f
            setPadding(0, 10, 0, 2)
        }
        root.addView(bulkLabel, lp())

        bulkInput = EditText(this).apply {
            hint = "01311241919 20\n01712345678 50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            textSize = 15f
        }
        root.addView(bulkInput, lp())

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

        val dumpButton = Button(this).apply {
            text = "📋 স্ক্রিন ডাম্প শেয়ার"
            setOnClickListener { shareNodeDump() }
        }
        root.addView(dumpButton, lp())

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 18, 0, 0)
        }
        root.addView(status, lp())

        // v19: নিচে লগ — প্রতিটা নম্বর, পাশে স্ট্যাটাস, ক্লিকে বিস্তারিত
        val logLabel = TextView(this).apply {
            text = "📝 লগ"
            textSize = 18f
            setPadding(0, 20, 0, 4)
        }
        root.addView(logLabel, lp())

        logList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(logList, lp())

        val warning = TextView(this).apply {
            text = "Security: PIN/Password encrypted storage-এ রাখা হবে। App uninstall/clear data করলে saved credentials মুছে যাবে।"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        root.addView(warning, lp())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        uiHandler.post(uiRefresh)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(uiRefresh)
        super.onPause()
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
        val bulk = parseBulk(bulkInput.text.toString())
        val requests: List<RechargeRequest> = if (bulk.isNotEmpty()) {
            val nonEmpty = bulkInput.text.toString().lines().count { it.trim().isNotEmpty() }
            if (nonEmpty > bulk.size) {
                toast("${nonEmpty - bulk.size}টি লাইন বোঝা যায়নি — বাকি ${bulk.size}টি চলছে")
            }
            bulk
        } else {
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
            listOf(RechargeRequest(phone, amount))
        }
        if (!isAccessibilityEnabled()) {
            toast("আগে Accessibility Permission দিন")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        saveCredentials()
        DriverSession.startBulk(requests)

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

    /** v15: প্রতি লাইনে "নম্বর টাকা" — যেমন "01311241919 20" */
    private fun parseBulk(text: String): List<RechargeRequest> {
        val out = ArrayList<RechargeRequest>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("[\\s,;]+"))
            if (parts.size >= 2 &&
                parts[0].matches(Regex("01\\d{9}")) &&
                (parts[1].toDoubleOrNull() ?: 0.0) > 0.0
            ) {
                out.add(RechargeRequest(parts[0], parts[1]))
            }
        }
        return out
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
        renderLog()
    }

    /** v19: নিচে লগ — নম্বরের পাশে স্ট্যাটাস (আপডেট হয়েছে কিনা দেখা যায়)। */
    private fun renderLog() {
        logList.removeAllViews()
        val reqs = DriverSession.queue
        if (reqs.isEmpty()) {
            logList.addView(TextView(this).apply {
                text = "এখনো কোনো রিকোয়েস্ট নেই"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            return
        }
        for (i in reqs.indices) {
            val req = reqs[i]
            val st = DriverSession.statuses.getOrNull(i).orEmpty().ifBlank { "—" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 12, 4, 12)
                isClickable = true
                isFocusable = true
            }
            val numTv = TextView(this).apply {
                text = "${req.phone}  •  ${req.amount} TK"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val stTv = TextView(this).apply {
                text = st
                textSize = 16f
                gravity = Gravity.END
            }
            row.addView(numTv)
            row.addView(stTv)
            row.setOnClickListener { showLogDetail(i) }
            logList.addView(row)
            logList.addView(View(this).apply {
                setBackgroundColor(Color.rgb(230, 230, 230))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 2
                )
            })
        }
    }

    /** v19: লগের রো-তে ক্লিক করলে বিস্তারিত দেখায়। */
    private fun showLogDetail(i: Int) {
        val req = DriverSession.queue.getOrNull(i) ?: return
        val st = DriverSession.statuses.getOrNull(i).orEmpty().ifBlank { "—" }
        val det = DriverSession.statusDetails.getOrNull(i).orEmpty()
        val msg = StringBuilder()
            .append("নম্বর: ${req.phone}\n")
            .append("পরিমাণ: ${req.amount} TK\n")
            .append("স্ট্যাটাস: $st\n")
        if (det.isNotBlank()) msg.append("বিস্তারিত: $det")
        AlertDialog.Builder(this)
            .setTitle("📝 লগ")
            .setMessage(msg.toString())
            .setPositiveButton("ঠিক আছে", null)
            .show()
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun shareNodeDump() {
        val dump = DriverSession.lastNodeDump
        if (dump.isBlank()) {
            toast("এখনো কোনো ডাম্প নেই — একবার RUN করে Cockpit-এর রিচার্জ স্ক্রিনে যান")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Cockpit screen dump")
            putExtra(Intent.EXTRA_TEXT, dump)
        }
        startActivity(Intent.createChooser(send, "ডাম্প পাঠান"))
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 6, 0, 6) }
}
