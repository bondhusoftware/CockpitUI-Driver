package com.bondhu.cockpitdriver

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class CockpitAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        DriverSession.lastMessage = "Accessibility Service চালু আছে"
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (!DriverSession.running) return
        val foregroundPackage = event?.packageName?.toString().orEmpty()
        if (foregroundPackage.isBlank() || !isLikelyCockpitPackage(foregroundPackage)) return

        // Give Cockpit a moment to finish rendering before acting.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ drive() }, 350)
    }

    override fun onInterrupt() {
        DriverSession.stop("Accessibility interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val knownCockpitPackages = setOf(
        "retail.grameenphone.com.gpretail",
        "com.grameenphone.cockpit"
    )

    private fun isLikelyCockpitPackage(packageName: String): Boolean {
        if (knownCockpitPackages.contains(packageName)) return true
        return try {
            val label = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
            label.contains("Cockpit", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun drive() {
        if (!DriverSession.running) return
        val root = rootInActiveWindow ?: return
        val currentPackage = root.packageName?.toString().orEmpty()
        if (currentPackage.isBlank() || !isLikelyCockpitPackage(currentPackage)) return

        if (containsAny(root, listOf("আপনার ID দিয়ে লগ ইন করুন", "আপনার পাসওয়ার্ড দিন"))) {
            if (fillLogin(root)) {
                DriverSession.state = DriverState.OPENING
                DriverSession.lastMessage = "Saved Cockpit credentials দেওয়া হয়েছে"
            } else {
                DriverSession.state = DriverState.OPENING
                DriverSession.lastMessage = "Cockpit login screen—credentials check করুন"
            }
            return
        }

        // Dashboard: v18 — fillAllRows() সব রো বসিয়ে true দিলে একবার পরবর্তী চাপবে।
        val onDashboard = containsAny(root, listOf("পরবর্তী")) &&
            containsAny(root, listOf("পাওয়ারলোড", "ফ্লেক্সিরিটেইল"))
        if (onDashboard) {
            // v26: ব্যাচ শেষে OK চেপে হোমে ফিরে এলে — পরের ব্যাচ থাকলে শুরু করো
            if (DriverSession.successCounted) {
                if (DriverSession.hasMoreBatches()) {
                    DriverSession.nextBatch()
                    // নতুন ব্যাচের ফিল-এ যাও (নিচে fall through)
                } else {
                    val total = DriverSession.masterRequests.size
                    val msg = if (total > 1) "🎉 সব রিচার্জ সম্পন্ন (${total}টি)" else "🎉 রিচার্জ সম্পন্ন"
                    DriverSession.stop(msg)
                    showToast(msg)
                    return
                }
            }
            // v26: PL ব্যাচ হলে পাওয়ারলোড ফ্লো
            if (DriverSession.isPlBatch && !DriverSession.plOfferNotFound) {
                handlePlDashboard(root)
                return
            }
            // ডায়াগনস্টিকের জন্য স্ক্রিনের node ডাম্প সংরক্ষণ করা হয়।
            DriverSession.lastNodeDump = captureNodeDump(root)
            // v21: single হলে v17-এর প্রমাণিত fill; বাল্ক হলে "+" রো ফিল।
            val filled = if (DriverSession.totalCount > 1) fillAllRows(root) else fillRechargeFields(root)
            if (!filled) {
                DriverSession.fillAttempts++
                if (DriverSession.fillAttempts > 8) {
                    val dbg8 = DriverSession.fillDebug
                    DriverSession.stop(
                        "ফিল্ডে বসানো যাচ্ছে না — manually চেক করুন" +
                            (if (dbg8.isNotBlank()) " [$dbg8]" else "")
                    )
                    return
                }
                DriverSession.state = DriverState.ENTERING_AMOUNT
                val total = DriverSession.totalCount
                val dbg = DriverSession.fillDebug
                DriverSession.lastMessage =
                    (if (total > 1) "বাল্ক: ${total}টি নম্বর বসানো হচ্ছে" else "নম্বর ও পরিমাণ বসানো হচ্ছে") +
                        (if (dbg.isNotBlank()) " [$dbg]" else "")
                handler.postDelayed({ drive() }, 900)
                return
            }
            DriverSession.fillAttempts = 0
            // v19: লগে সব নম্বরের স্ট্যাটাস "চলছে"
            DriverSession.setAllStatus("🔄 চলছে")

            if (DriverSession.nextAttempts >= 3) {
                DriverSession.stop("পরবর্তী চাপা যায়নি — manually চাপুন")
                return
            }
            DriverSession.state = DriverState.TAP_NEXT
            DriverSession.lastMessage = "পরবর্তী চাপা হচ্ছে"
            if (clickText(root, listOf("পরবর্তী"))) {
                DriverSession.nextAttempts++
            }
            handler.postDelayed({ drive() }, 900)
            return
        }

        // v14: Confirm page — ERS PIN বসিয়ে "নিশ্চিত করুন" চাপবে।
        // v13-এর বাগ: password ফিল্ডের text পড়ে যাচাই করা যায় না (•••• থাকে),
        // তাই বারবার মুছে-বসানোর লুপে আটকে যেত। এখন PIN গৃহীত হয়েছে কিনা
        // "নিশ্চিত করুন" বাটনের enabled state দেখে বোঝে — এটাই অ্যাপের নিজের সংকেত।
        val onConfirmPage = containsAny(root, listOf("রিচার্জ নিশ্চিত করুন")) &&
            containsAny(root, listOf("ERS PIN", "নিশ্চিত করুন"))
        if (onConfirmPage) {
            // v18: সব রিকোয়েস্টের নম্বর+পরিমাণ কনফার্ম পেজে মিলতে হবে, তবেই সাবমিট।
            val reqs = DriverSession.queue
            val numbersOk = reqs.all { containsText(root, it.phone) }
            val amountsOk = reqs.all { confirmAmountMatches(root, it.amount) }
            if (!numbersOk || !amountsOk) {
                DriverSession.stop("নম্বর/পরিমাণ মিলছে না — manually চেক করুন")
                return
            }
            val pin = SecureStore.get(this, "ers_pin")
            if (pin.isBlank()) {
                DriverSession.stop("ERS PIN সেভ করা নেই — ড্রাইভার অ্যাপে ERS PIN দিন")
                return
            }
            val confirmBtn = findButtonByExactText(root, "নিশ্চিত করুন")
            if (confirmBtn != null && confirmBtn.isEnabled) {
                if (DriverSession.confirmAttempts >= 4) {
                    DriverSession.stop("নিশ্চিত করুন চাপা যায়নি — manually চাপুন")
                    return
                }
                DriverSession.state = DriverState.SUBMITTING
                DriverSession.lastMessage = "নিশ্চিত করুন চাপা হচ্ছে"
                DriverSession.confirmAttempts++
                if (!confirmBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clickText(root, listOf("নিশ্চিত করুন"))
                }
                handler.postDelayed({ drive() }, 1500)
                return
            }
            // বাটন এখনো enable হয়নি — PIN পৌঁছেছে কিনা নিশ্চিত করো (সর্বোচ্চ ৩ বার বসাবে)।
            DriverSession.state = DriverState.WAITING_PIN
            DriverSession.pinAttempts++
            if (DriverSession.pinAttempts <= 3) {
                DriverSession.lastMessage = "ERS PIN বসানো হচ্ছে"
                fillPinOnce(root, pin)
            } else {
                DriverSession.lastMessage = "নিশ্চিত করুন বাটন enable-এর অপেক্ষায়…"
            }
            if (DriverSession.pinAttempts > 20) {
                DriverSession.stop("PIN গৃহীত হচ্ছে না (ভুল PIN হতে পারে) — manually চেক করুন")
                return
            }
            handler.postDelayed({ drive() }, 1000)
            return
        }

        // v18: সফল — এক ট্রানজাকশনেই সব শেষ। OK চেপে হোমে ফেরো।
        if (containsAny(root, listOf("সফল", "সফলভাবে", "Success", "Transaction Details", "ট্রানজেকশন বিস্তারিত"))) {
            if (!DriverSession.successCounted) {
                DriverSession.successCounted = true
                DriverSession.okAttempts = 0
                DriverSession.completedCount = DriverSession.totalCount
                val total = DriverSession.totalCount
                val msg = if (total > 1) "🎉 বাল্ক রিচার্জ সম্পন্ন (${total}টি)" else "🎉 রিচার্জ সম্পন্ন"
                DriverSession.lastMessage = "$msg — OK চেপে হোমে ফিরছে"
                showToast(msg)
                // v19: লগে সব নম্বর সফল
                DriverSession.setAllStatus("✅ সফল", "সফল — ${DriverSession.nowTime()}")
            }
            if (DriverSession.okAttempts >= 4) {
                DriverSession.stop("🎉 রিচার্জ সম্পন্ন — OK manually চেপে হোমে যান")
                return
            }
            DriverSession.state = DriverState.DASHBOARD
            DriverSession.okAttempts++
            tapOkButton(root)
            handler.postDelayed({ drive() }, 1500)
            return
        }

        if (containsAny(root, listOf("ব্যর্থ", "Failed", "failure", "দুঃখিত"))) {
            DriverSession.state = DriverState.FAILED
            DriverSession.failedCount++
            val ok = DriverSession.completedCount
            val total = DriverSession.totalCount
            DriverSession.lastMessage =
                "❌ ${DriverSession.phone} ব্যর্থ — থামানো হয়েছে (✅$ok/$total সফল)"
            // v19: লগে সব নম্বর ব্যর্থ
            DriverSession.setAllStatus("❌ ব্যর্থ", DriverSession.lastMessage)
            DriverSession.running = false
            showToast("❌ রিচার্জ ব্যর্থ: ${DriverSession.phone}")
        }
    }

    /**
     * v21: v17-এর প্রমাণিত single fill হুবহু ফিরিয়ে আনা হলো।
     * (v18-এর নতুন fillAllRows কিছু ডিভাইসে ফিল্ড চিনতে ব্যর্থ হচ্ছিল।)
     */
    private fun fillRechargeFields(root: AccessibilityNodeInfo): Boolean {
        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)
        if (edits.size < 2) return false

        var phoneField: AccessibilityNodeInfo? = null
        var amountField: AccessibilityNodeInfo? = null
        for (e in edits) {
            val hint = e.hintText?.toString().orEmpty()
            if (hint.contains("পরিমাণ") || hint.contains("পরিমান") || hint.contains("amount", ignoreCase = true)) {
                amountField = e
            } else if (phoneField == null) {
                phoneField = e
            }
        }
        if (phoneField == null) phoneField = edits[0]
        if (amountField == null) {
            amountField = edits.firstOrNull { it != phoneField } ?: edits[1]
        }

        // v13: digit-exact যাচাই — "2020"-এর মধ্যে "20" পেয়ে ভুয়া-সফল হবে না।
        var phoneSet = fieldMatches(phoneField, DriverSession.phone)
        var amountSet = fieldMatches(amountField, DriverSession.amount)

        if (!phoneSet) {
            // v13: আগে খালি করে তারপর একবারে লেখে — "২০২০" ধরনের অবশিষ্টাংশ থাকবে না।
            clearField(phoneField)
            if (setTextRobust(phoneField, DriverSession.phone)) {
                phoneSet = fieldMatches(phoneField, DriverSession.phone)
            }
        }

        // Amount: try the detected field first, then ALL other editable fields
        if (!amountSet) {
            val tried = mutableSetOf<AccessibilityNodeInfo>()
            if (amountField != null) {
                tried.add(amountField)
                clearField(amountField)
                if (setTextRobust(amountField, DriverSession.amount)) {
                    Thread.sleep(300)
                    if (fieldMatches(amountField, DriverSession.amount)) amountSet = true
                }
            }
            if (!amountSet) {
                for (e in edits) {
                    if (e == phoneField || e in tried) continue
                    clearField(e)
                    if (setTextRobust(e, DriverSession.amount)) {
                        // Verify the text actually stuck
                        Thread.sleep(300)
                        if (fieldMatches(e, DriverSession.amount)) {
                            amountSet = true
                            break
                        }
                    }
                }
            }
        }

        return phoneSet && amountSet
    }

    /**
     * v26: PL ড্যাশবোর্ড ফ্লো —
     * stage 0: নম্বর+পরিমাণ বসাও → stage 1
     * stage 1: পাওয়ারলোড বাটন চাপো → stage 2
     * stage 2: দাম মিলিয়ে অফারে ট্যাপ করো (না পেলে "সব পি.এল" ট্যাব, তাও না পেলে normal flow)
     * stage 3+: অফার সিলেক্ট — standard flow (পরবর্তী → PIN → নিশ্চিত)
     */
    private fun handlePlDashboard(root: AccessibilityNodeInfo) {
        DriverSession.lastNodeDump = captureNodeDump(root)
        when (DriverSession.plStage) {
            0 -> {
                if (!fillRechargeFields(root)) {
                    DriverSession.fillAttempts++
                    if (DriverSession.fillAttempts > 8) {
                        val dbg8 = DriverSession.fillDebug
                        DriverSession.stop(
                            "ফিল্ডে বসানো যাচ্ছে না — manually চেক করুন" +
                                (if (dbg8.isNotBlank()) " [$dbg8]" else "")
                        )
                        return
                    }
                    DriverSession.state = DriverState.ENTERING_AMOUNT
                    DriverSession.lastMessage = "⚡ নম্বর ও পরিমাণ বসানো হচ্ছে" +
                        (if (DriverSession.fillDebug.isNotBlank()) " [${DriverSession.fillDebug}]" else "")
                    handler.postDelayed({ drive() }, 900)
                    return
                }
                DriverSession.fillAttempts = 0
                DriverSession.setAllStatus("🔄 চলছে")
                DriverSession.plStage = 1
                DriverSession.lastMessage = "⚡ পাওয়ারলোড বাটন চাপা হচ্ছে"
                handler.postDelayed({ drive() }, 700)
                return
            }
            1 -> {
                // v26: gesture ট্যাপ (performAction শুধু ফোকাস করে)
                if (tapPowerloadButton(root)) {
                    DriverSession.plStage = 2
                    DriverSession.lastMessage = "⚡ অফার লিস্ট আসছে — অপেক্ষা"
                } else {
                    DriverSession.fillDebug = "পাওয়ারলোড বাটন পাওয়া যায়নি"
                    DriverSession.lastMessage = "⚡ পাওয়ারলোড বাটন পাওয়া যায়নি — আবার চেষ্টা"
                }
                handler.postDelayed({ drive() }, 2000)
                return
            }
            2 -> {
                val amount = DriverSession.amount
                // v26: অফার লিস্ট এসেছে কিনা দেখো (৳ বা ট্যাব আছে কিনা)
                val hasOfferList = containsAny(root, listOf("৳")) ||
                    containsAny(root, listOf("সব পি.এল", "ডাটা", "বান্ডেল"))
                if (!hasOfferList) {
                    // লিস্ট এখনো আসেনি — একটু অপেক্ষা করো (সর্বোচ্চ ~৮ সেকেন্ড)
                    DriverSession.plWaitCount++
                    if (DriverSession.plWaitCount > 5) {
                        // তাও না এলে normal flow-তে ফিরে যাও
                        DriverSession.plOfferNotFound = true
                        DriverSession.isPlBatch = false
                        DriverSession.lastMessage = "⚡ অফার লিস্ট আসেনি — normal রিচার্জ হচ্ছে"
                        handler.postDelayed({ drive() }, 700)
                        return
                    }
                    DriverSession.lastMessage = "⚡ অফার লিস্টের অপেক্ষায়…"
                    handler.postDelayed({ drive() }, 1500)
                    return
                }
                DriverSession.plWaitCount = 0
                if (tapOfferWithPrice(root, amount)) {
                    DriverSession.plStage = 3
                    DriverSession.lastMessage = "⚡ অফার সিলেক্ট হয়েছে — পরের পেজের অপেক্ষায়"
                    handler.postDelayed({ drive() }, 1500)
                    return
                }
                // অফার পাওয়া যায়নি — "সব পি.এল" ট্যাবে দেখো
                if (tapAllPlTab(root)) {
                    DriverSession.lastMessage = "⚡ সব পাওয়ারলোড অফার দেখা হচ্ছে"
                    handler.postDelayed({ drive() }, 1500)
                    return
                }
                // তাও না পেলে normal flow-তে ফিরে যাও (সরাসরি পরবর্তী)
                DriverSession.plOfferNotFound = true
                DriverSession.isPlBatch = false
                DriverSession.lastMessage = "⚡ অফার পাওয়া যায়নি — normal রিচার্জ হচ্ছে"
                handler.postDelayed({ drive() }, 700)
                return
            }
            else -> {
                // stage 3+: অফার সিলেক্ট হয়ে গেছে — standard flow
                // (এখানে এলে dashboard-এ ফিরে এসেছে, পরবর্তী চাপো)
                if (DriverSession.nextAttempts >= 3) {
                    DriverSession.stop("পরবর্তী চাপা যায়নি — manually চাপুন")
                    return
                }
                DriverSession.state = DriverState.TAP_NEXT
                DriverSession.lastMessage = "⚡ পরবর্তী চাপা হচ্ছে"
                if (clickText(root, listOf("পরবর্তী"))) {
                    DriverSession.nextAttempts++
                }
                handler.postDelayed({ drive() }, 900)
                return
            }
        }
    }

    /**
     * v23: বাল্ক — একটা রো ভরো → আরও নম্বর থাকলে "+" চেপে পরের রো → ভরো।
     * (সর্বোচ্চ ৫ রো — Cockpit-এর সীমা।)
     */
    private fun fillAllRows(root: AccessibilityNodeInfo): Boolean {
        val requests = DriverSession.queue
        if (requests.isEmpty()) {
            DriverSession.fillDebug = "queue খালি"
            return false
        }
        var cur = root

        // 0. বাড়তি খালি রো থাকলে "-" চেপে সরাও
        var cleanupGuard = 0
        while (countRows(cur) > requests.size && cleanupGuard < 6) {
            cleanupGuard++
            val pairedC = getPairedRows(cur)
            val lastIdx = pairedC.first.size - 1
            if (lastIdx < 0 || !isRowEmpty(pairedC.first, pairedC.second, lastIdx)) break
            if (!tapMinusOnLastRow(cur)) break
            Thread.sleep(900)
            cur = rootInActiveWindow ?: break
        }

        // 1. প্রতি রো: দরকার হলে "+" চেপে বানাও (নতুন রো আসা পর্যন্ত অপেক্ষা করে), তারপর ভরো
        for (i in requests.indices) {
            var guard = 0
            while (countRows(cur) <= i && guard < 3) {
                guard++
                val before = countRows(cur)
                if (!tapPlusButton(cur)) {
                    DriverSession.fillDebug = "প্লাস বাটন পাওয়া যায়নি (রো ${i + 1})"
                    return false
                }
                // v25: fixed sleep নয় — নতুন রো আসা পর্যন্ত অপেক্ষা করো
                var waited = 0
                var after = before
                while (after <= before && waited < 10) {
                    Thread.sleep(500)
                    waited++
                    val f = rootInActiveWindow
                    if (f != null) {
                        cur = f
                        after = countRows(cur)
                    }
                }
                if (after <= before) {
                    DriverSession.fillDebug = "প্লাস চাপা গেল কিন্তু রো বাড়েনি"
                    return false
                }
            }
            if (countRows(cur) <= i) {
                DriverSession.fillDebug = "রো ${i + 1} বানানো যায়নি"
                return false
            }
            // রো i-তে নম্বর+পরিমাণ বসাও (v17-এর check-first প্যাটার্ন)
            val paired = getPairedRows(cur)
            if (paired.first.size <= i || paired.second.size <= i) {
                DriverSession.fillDebug = "ফিল্ড জোড়া মেলেনি (রো ${i + 1})"
                return false
            }
            val nf = paired.first[i]
            val af = paired.second[i]
            if (!fieldMatches(nf, requests[i].phone)) {
                clearField(nf)
                setTextRobust(nf, requests[i].phone)
            }
            if (!fieldMatches(af, requests[i].amount)) {
                clearField(af)
                setTextRobust(af, requests[i].amount)
            }
            Thread.sleep(250)
            cur = rootInActiveWindow ?: cur
        }
        // যাচাই: সব রো-তে ঠিক নম্বর+পরিমাণ বসেছে কিনা
        Thread.sleep(400)
        val r2 = rootInActiveWindow
        if (r2 == null) {
            DriverSession.fillDebug = "রুট নাল (যাচাইয়ে)"
            return false
        }
        val paired2 = getPairedRows(r2)
        if (paired2.first.size < requests.size || paired2.second.size < requests.size) {
            DriverSession.fillDebug = "যাচাইয়ে ফিল্ড মেলেনি"
            return false
        }
        for (i in requests.indices) {
            if (!fieldMatches(paired2.first[i], requests[i].phone)) {
                DriverSession.fillDebug = "যাচাই ব্যর্থ: রো ${i + 1} নম্বর"
                return false
            }
            if (!fieldMatches(paired2.second[i], requests[i].amount)) {
                DriverSession.fillDebug = "যাচাই ব্যর্থ: রো ${i + 1} পরিমাণ"
                return false
            }
        }
        DriverSession.fillDebug = ""
        return true
    }

    /** v24: amount hint চেনার হেলপার। */
    private fun isAmountHint(hint: String): Boolean {
        return hint.contains("পরিমাণ") || hint.contains("পরিমান") ||
                hint.contains("amount", ignoreCase = true)
    }

    private fun hintOf(e: AccessibilityNodeInfo): String {
        return try { e.hintText?.toString().orEmpty() } catch (_: Exception) { "" }
    }

    private fun boundsLeft(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        return try {
            n.getBoundsInScreen(r)
            r.left
        } catch (_: Exception) {
            0
        }
    }

    /**
     * v24: রো-এর ফিল্ড জোড়া মিলায়। প্রথমে hint দিয়ে, না মিললে
     * v17-স্টাইলে position অনুযায়ী (উপর-নিচ, তারপর বাম-ডান) জোড়া মিলায়।
     * (amount-এর hint সবসময় পাওয়া যায় না — v17-এর fallback-ই আসলে কাজ করত।)
     */
    private fun getPairedRows(root: AccessibilityNodeInfo): Pair<List<AccessibilityNodeInfo>, List<AccessibilityNodeInfo>> {
        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)

        // 1. hint-based
        val numbers = ArrayList<AccessibilityNodeInfo>()
        val amounts = ArrayList<AccessibilityNodeInfo>()
        for (e in edits) {
            if (isAmountHint(hintOf(e))) amounts.add(e) else numbers.add(e)
        }
        numbers.sortBy { boundsTop(it) }
        amounts.sortBy { boundsTop(it) }
        if (amounts.isNotEmpty() && numbers.size == amounts.size) {
            return numbers to amounts
        }

        // 2. positional fallback: (top, left) সর্ট করে পরপর জোড়া
        val sorted = edits.sortedWith(compareBy({ boundsTop(it) }, { boundsLeft(it) }))
        val pNumbers = ArrayList<AccessibilityNodeInfo>()
        val pAmounts = ArrayList<AccessibilityNodeInfo>()
        var idx = 0
        while (idx + 1 < sorted.size) {
            pNumbers.add(sorted[idx])
            pAmounts.add(sorted[idx + 1])
            idx += 2
        }
        return pNumbers to pAmounts
    }

    /** v24: আসল রো সংখ্যা — getPairedRows থেকে। */
    private fun countRows(root: AccessibilityNodeInfo): Int {
        return getPairedRows(root).first.size
    }

    private fun boundsTop(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        return try {
            n.getBoundsInScreen(r)
            r.top
        } catch (_: Exception) {
            0
        }
    }

    /** v25: শেষের রো-র "−" বাটন চেপে ওই রো সরায়। */
    private fun tapMinusOnLastRow(root: AccessibilityNodeInfo): Boolean {
        val minuses = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            try {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t == "−" || t == "-") minuses.add(n)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            } catch (_: Exception) { }
        }
        walk(root)
        if (minuses.isEmpty()) return false
        val target = minuses.maxByOrNull { boundsTop(it) } ?: return false
        var c: AccessibilityNodeInfo? = target
        repeat(6) {
            val cur = c ?: return@repeat
            if (cur.isClickable) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            c = cur.parent
        }
        val r = Rect()
        return try {
            target.getBoundsInScreen(r)
            if (!r.isEmpty) tapAt(r.centerX().toFloat(), r.centerY().toFloat()) else false
        } catch (_: Exception) {
            false
        }
    }

    /** v25: রো-র দুটো ফিল্ডই খালি কিনা। */
    private fun isRowEmpty(
        numbers: List<AccessibilityNodeInfo>,
        amounts: List<AccessibilityNodeInfo>,
        idx: Int
    ): Boolean {
        if (idx >= numbers.size || idx >= amounts.size) return true
        return try {
            val n = numbers[idx].text?.toString().orEmpty()
            val a = amounts[idx].text?.toString().orEmpty()
            digitsOnly(n).isEmpty() && digitsOnly(a).isEmpty()
        } catch (_: Exception) {
            true
        }
    }

    /**
     * v26: পাওয়ারলোড — "৳" দাম খুঁজে হুবহু মিলিয়ে অফার কার্ডে ট্যাপ করে।
     * দাম বাংলা ডিজিটে থাকতে পারে (যেমন "৳ ৯৮") — digitsOnly দুটোই হ্যান্ডেল করে।
     */
    private fun tapOfferWithPrice(root: AccessibilityNodeInfo, amount: String): Boolean {
        val want = digitsOnly(amount)
        if (want.isEmpty()) return false
        val priceNodes = ArrayList<AccessibilityNodeInfo>()
        collectWithText(root, "৳", priceNodes)
        for (pn in priceNodes) {
            try {
                pn.refresh()
                val priceText = pn.text?.toString().orEmpty()
                if (digitsOnly(priceText) != want) continue
                // দাম মিলেছে — কার্ডটা (clickable ancestor) খুঁজে ট্যাপ করো
                var c: AccessibilityNodeInfo? = pn
                repeat(8) {
                    val cur = c ?: return@repeat
                    if (cur.isClickable) {
                        if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                        return@repeat
                    }
                    c = cur.parent
                }
                // clickable ancestor না পেলে gesture-এ ট্যাপ
                val r = Rect()
                pn.getBoundsInScreen(r)
                if (!r.isEmpty && tapAt(r.centerX().toFloat(), r.centerY().toFloat())) return true
            } catch (_: Exception) { }
        }
        return false
    }

    /** v26: "সব পি.এল" ট্যাবে সব পাওয়ারলোড অফার দেখায়। */
    private fun tapAllPlTab(root: AccessibilityNodeInfo): Boolean {
        return clickText(root, listOf("সব পি.এল", "সব পি এল"))
    }

    /**
     * v28: পাওয়ারলোড বাটনে ট্যাপ — টেক্সট নোডের bounds-এ সরাসরি gesture।
     * (v27-এর বাগ: outermost clickable ancestor-এর center-এ ট্যাপ যাচ্ছিল,
     *  যা বাটনের বাইরে পড়তে পারে। টেক্সট বাটনের ভেতরেই থাকে।)
     */
    private fun tapPowerloadButton(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByText(root, "পাওয়ারলোড") ?: return false
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (_: Exception) {
            return false
        }
        if (r.isEmpty) return false
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    /** v22: "+" বাটন চেপে নতুন রো নেয় — text, content-desc, তারপর gesture। */
    private fun tapPlusButton(root: AccessibilityNodeInfo): Boolean {        val btn = findButtonByExactText(root, "+")
        if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // content-desc-এ "+" বা "add" থাকলে (ImageButton ধরনের বাটন)
        val byDesc = findClickableByDesc(root, listOf("+", "add"))
        if (byDesc != null) {
            if (byDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val r = Rect()
            try {
                byDesc.getBoundsInScreen(r)
                if (!r.isEmpty) return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
            } catch (_: Exception) { }
        }
        return clickText(root, listOf("+"))
    }

    /** v22: content-desc-এ keyword মিলিয়ে clickable node খোঁজে। */
    private fun findClickableByDesc(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        var fallback: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || result != null) return
            try {
                val desc = n.contentDescription?.toString().orEmpty()
                if (keywords.any { desc.contains(it, ignoreCase = true) }) {
                    var c: AccessibilityNodeInfo? = n
                    repeat(6) {
                        val cur = c ?: return@repeat
                        if (cur.isClickable) {
                            result = cur
                            return
                        }
                        c = cur.parent
                    }
                    if (fallback == null) fallback = n
                }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            } catch (_: Exception) { }
        }
        walk(root)
        return result ?: fallback
    }

    /** v13: শুধু সংখ্যা রাখে; বাংলা সংখ্যাকেও ASCII-তে নরমালাইজ করে। */
    private fun digitsOnly(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in '0'..'9') sb.append(c)
            else if (c in '০'..'৯') sb.append('0' + (c - '০'))
        }
        return sb.toString()
    }

    /**
     * v13: ফিল্ডের ভেতরের সংখ্যা value-এর সংখ্যার সাথে হুবহু মিলছে কিনা।
     * "2020"-এর মধ্যে "20" খুঁজে পেয়ে ভুয়া-সফল হবে না; "৳ 20" বা "20.0"-ও মিলবে।
     */
    private fun fieldMatches(field: AccessibilityNodeInfo?, value: String): Boolean {
        if (field == null) return false
        val want = digitsOnly(value)
        if (want.isEmpty()) return false
        return try {
            field.refresh()
            val have = field.text?.toString().orEmpty()
            if (digitsOnly(have) == want) return true
            // "20" vs "20.0" — সংখ্যা হিসেবে তুলনা
            val wantNum = value.trim().toDoubleOrNull()
            val haveNum = have.trim().toDoubleOrNull()
            wantNum != null && haveNum != null && wantNum == haveNum
        } catch (_: Exception) {
            false
        }
    }

    /** v13: ফিল্ড আগে খালি করে — যাতে পুরনো "2020"-ধরনের অবশিষ্টাংশ না থাকে। */
    private fun clearField(node: AccessibilityNodeInfo) {
        try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Thread.sleep(200)
        } catch (_: Exception) { }
    }

    /**
     * v12 diagnostic: accessibility tree-এর সংক্ষিপ্ত ডাম্প।
     * amount ফিল্ডটা আসলে কী ধরনের view (class/hint/editable) তা দেখার জন্য —
     * MainActivity-র "স্ক্রিন ডাম্প শেয়ার" বোতামে পাঠানো যায়।
     */
    private fun captureNodeDump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sb.append("package=").append(root.packageName).append('\n')
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= 800) return
            count++
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val text = node.text?.toString()?.take(40).orEmpty().replace('\n', ' ')
            val hint = try {
                node.hintText?.toString()?.take(30).orEmpty()
            } catch (_: Exception) {
                ""
            }
            val desc = node.contentDescription?.toString()?.take(30).orEmpty().replace('\n', ' ')
            val flags = StringBuilder()
            if (node.isEditable) flags.append('E')
            if (node.isFocusable) flags.append('F')
            if (node.isFocused) flags.append('*')
            if (node.isClickable) flags.append('C')
            if (node.isScrollable) flags.append('S')
            val b = Rect()
            try {
                node.getBoundsInScreen(b)
            } catch (_: Exception) { }
            sb.append("  ".repeat(minOf(depth, 12)))
                .append('[').append(cls).append(']')
                .append(if (flags.isNotEmpty()) " {$flags}" else "")
                .append(if (text.isNotEmpty()) " text=\"$text\"" else "")
                .append(if (hint.isNotEmpty()) " hint=\"$hint\"" else "")
                .append(if (desc.isNotEmpty()) " desc=\"$desc\"" else "")
                .append(" [${b.left},${b.top}][${b.right},${b.bottom}]")
                .append('\n')
            for (i in 0 until node.childCount) {
                try {
                    walk(node.getChild(i), depth + 1)
                } catch (_: Exception) { }
            }
        }
        try {
            walk(root, 0)
        } catch (_: Exception) { }
        sb.append("nodes=").append(count).append('\n')
        return sb.toString()
    }

    private fun setTextRobust(node: AccessibilityNodeInfo, value: String): Boolean {
        if (setText(node, value)) {
            Thread.sleep(200)
            if (fieldMatches(node, value)) return true
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(400)
            if (setText(node, value)) {
                Thread.sleep(200)
                if (fieldMatches(node, value)) return true
            }
        } catch (_: Exception) { }
        try {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("recharge", value))
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(400)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(300)
            try {
                val selectArgs = Bundle()
                selectArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                selectArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 999)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
                Thread.sleep(200)
            } catch (_: Exception) { }
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Thread.sleep(300)
                if (fieldMatches(node, value)) return true
            }
        } catch (_: Exception) { }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(400)
            if (setText(node, value)) {
                Thread.sleep(250)
                return fieldMatches(node, value)
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    private fun fillLogin(root: AccessibilityNodeInfo): Boolean {
        val id = SecureStore.get(this, "cockpit_id")
        val password = SecureStore.get(this, "cockpit_password")
        if (id.isBlank() || password.isBlank()) return false

        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)
        if (edits.size < 2) return false

        var filledId = false
        var filledPassword = false

        for (node in edits) {
            val hint = node.hintText?.toString().orEmpty().lowercase()
            val cls = node.className?.toString().orEmpty()

            val isPassword = node.isPassword || hint.contains("password") || hint.contains("পাসওয়ার্ড")
            val isId = hint.contains("id") || hint.contains("pos") || hint.contains("আইডি")

            val value = when {
                isPassword && !filledPassword -> password
                isId && !filledId -> id
                !filledId -> id
                !filledPassword -> password
                else -> null
            } ?: continue

            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                if (isPassword || filledId) filledPassword = true else filledId = true
            }
        }

        // Fallback for Cockpit versions that expose no useful hints.
        if (!filledId || !filledPassword) {
            if (edits.size >= 2) {
                setText(edits[0], id)
                setText(edits[1], password)
                filledId = true
                filledPassword = true
            }
        }

        return filledId && filledPassword
    }

    /**
     * v14: PIN ফিল্ডে একবার PIN বসায় (clear + set)। password ফিল্ডের text পড়ে
     * যাচাই করা যায় না (•••• থাকে) — সফল কিনা "নিশ্চিত করুন" বাটনের
     * enabled state দেখে drive() লুপই ঠিক করবে।
     */
    private fun fillPinOnce(root: AccessibilityNodeInfo, pin: String): Boolean {
        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)

        var pinField: AccessibilityNodeInfo? = null
        for (node in edits) {
            val hint = node.hintText?.toString().orEmpty().lowercase()
            if (hint.contains("ers") || hint.contains("pin") || hint.contains("পিন")) {
                pinField = node
                break
            }
        }
        // Confirm পেজে সাধারণত একটাই editable field থাকে (PIN-এর ঘর)।
        if (pinField == null && edits.size == 1) pinField = edits[0]
        if (pinField == null) return false

        clearField(pinField)
        Thread.sleep(200)
        return try {
            pinField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
            if (setText(pinField, pin)) return true
            // Fallback: clipboard paste
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("pin", pin))
            pinField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v15: exact লেখা মিলিয়ে বাটনটা খুঁজে তার clickable ancestor নেয়।
     * ("রিচার্জ নিশ্চিত করুন" টাইটেলকে এড়াতে exact match — substring নয়।)
     */
    private fun findButtonByExactText(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || result != null) return
            try {
                if (n.text?.toString()?.trim() == label) {
                    var c: AccessibilityNodeInfo? = n
                    repeat(6) {
                        val cur = c ?: return@repeat
                        if (cur.isClickable) {
                            result = cur
                            return
                        }
                        c = cur.parent
                    }
                }
                for (i in 0 until n.childCount) {
                    walk(n.getChild(i))
                }
            } catch (_: Exception) { }
        }
        walk(root)
        return result
    }

    /** v15: success/transaction পেজের OK বাটন চাপে (বাল্ক: হোমে ফেরার জন্য)। */
    private fun tapOkButton(root: AccessibilityNodeInfo): Boolean {
        val btn = findButtonByExactText(root, "OK")
        if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return clickText(root, listOf("OK"))
    }

    /**
     * v13: confirm পেজে দেখানো টাকার অঙ্ক (যেমন "20 TK") আমাদের amount-এর সাথে
     * মেলে কিনা। না মিললে রিচার্জ সাবমিট করা হবে না (টাকার গরমিল রোধে)।
     */
    private fun confirmAmountMatches(root: AccessibilityNodeInfo, amount: String): Boolean {
        val want = digitsOnly(amount)
        if (want.isEmpty()) return false
        val priceNodes = ArrayList<AccessibilityNodeInfo>()
        // v26: "TK" বা "৳" — দুটোই দামের চিহ্ন হতে পারে
        collectWithText(root, "TK", priceNodes)
        collectWithText(root, "৳", priceNodes)
        for (n in priceNodes) {
            try {
                n.refresh()
                if (digitsOnly(n.text?.toString().orEmpty()) == want) return true
            } catch (_: Exception) { }
        }
        return false
    }

    private fun collectWithText(node: AccessibilityNodeInfo, wanted: String, out: MutableList<AccessibilityNodeInfo>) {
        try {
            if (node.text?.toString()?.contains(wanted, ignoreCase = true) == true) out.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collectWithText(it, wanted, out) }
            }
        } catch (_: Exception) { }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun collectEditable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectEditable(it, out) }
        }
    }

    private fun containsText(root: AccessibilityNodeInfo, wanted: String): Boolean {
        return findNodeByText(root, wanted) != null
    }

    private fun containsAny(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        return texts.any { containsText(root, it) }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        val exact = root.findAccessibilityNodeInfosByText(wanted)
        if (exact.isNotEmpty()) return exact[0]

        val text = root.text?.toString().orEmpty()
        val desc = root.contentDescription?.toString().orEmpty()
        if (text.contains(wanted, ignoreCase = true) || desc.contains(wanted, ignoreCase = true)) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByText(child, wanted)
            if (found != null) return found
        }
        return null
    }

    private fun clickText(root: AccessibilityNodeInfo, candidates: List<String>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 700) return false

        for (candidate in candidates) {
            val node = findNodeByText(root, candidate) ?: continue
            var current: AccessibilityNodeInfo? = node
            repeat(5) {
                val c = current
                if (c?.isClickable == true) {
                    lastActionAt = now
                    return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = c?.parent
            }
            if (node.isClickable) {
                lastActionAt = now
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // Fallback: no clickable node exposed -> tap the centre of its bounds.
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                lastActionAt = now
                return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
            }
        }
        return false
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
