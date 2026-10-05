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

        // Dashboard: fill number + amount, then SKIP Powerload and tap Next (পরবর্তী).
        // Previous bug: once both fields were filled, fillRechargeFields() returned true
        // and we re-posted drive() forever, so the Next branch was never reached.
        val onDashboard = containsAny(root, listOf("পরবর্তী")) &&
            containsAny(root, listOf("পাওয়ারলোড", "ফ্লেক্সিরিটেইল"))
        if (onDashboard) {
            val phoneSet = containsText(root, DriverSession.phone)
            val amountSet = containsText(root, DriverSession.amount)

            if (!phoneSet || !amountSet) {
                fillRechargeFields(root)
                DriverSession.state = DriverState.ENTERING_AMOUNT
                DriverSession.lastMessage = "নম্বর ও পরিমাণ বসানো হচ্ছে"
                handler.postDelayed({ drive() }, 450)
                return
            }

            if (DriverSession.nextAttempts >= 3) {
                DriverSession.stop("পরবর্তী চাপা যায়নি — manually চাপুন")
                return
            }
            DriverSession.state = DriverState.TAP_NEXT
            DriverSession.lastMessage = "পাওয়ারলোড skip করে পরবর্তী চাপা হচ্ছে"
            if (clickText(root, listOf("পরবর্তী"))) {
                DriverSession.nextAttempts++
            }
            handler.postDelayed({ drive() }, 900)
            return
        }

        if (containsAny(root, listOf("রিচার্জ নিশ্চিত করুন")) && containsText(root, DriverSession.phone)) {
            DriverSession.state = DriverState.CONFIRMATION
            DriverSession.lastMessage = "Confirmation screen পাওয়া গেছে"
            return
        }

        if (containsAny(root, listOf("ERS PIN দিন"))) {
            if (fillErsPin(root)) {
                DriverSession.state = DriverState.SUBMITTING
                DriverSession.lastMessage = "Saved ERS PIN দেওয়া হয়েছে"
                handler.postDelayed({ drive() }, 450)
            } else {
                DriverSession.state = DriverState.WAITING_PIN
                DriverSession.lastMessage = "ERS PIN field পাওয়া গেছে, কিন্তু fill করা যায়নি"
            }
            return
        }

        if (containsAny(root, listOf("নিশ্চিত করুন", "নিশ্চিতকরন", "Confirm")) &&
            (DriverSession.state == DriverState.SUBMITTING || DriverSession.state == DriverState.WAITING_PIN)) {
            DriverSession.state = DriverState.SUBMITTING
            DriverSession.lastMessage = "নিশ্চিত করুন চাপা হচ্ছে"
            clickText(root, listOf("নিশ্চিত করুন", "নিশ্চিতকরন", "Confirm"))
            return
        }

        if (containsAny(root, listOf("সফল", "সফলভাবে", "Success", "Transaction Details"))) {
            DriverSession.state = DriverState.SUCCESS
            DriverSession.lastMessage = "Recharge success screen পাওয়া গেছে"
            DriverSession.running = false
            showToast("Recharge Success")
            return
        }

        if (containsAny(root, listOf("ব্যর্থ", "Failed", "failure", "দুঃখিত"))) {
            DriverSession.state = DriverState.FAILED
            DriverSession.lastMessage = "Recharge failed screen পাওয়া গেছে"
            DriverSession.running = false
            showToast("Recharge Failed")
        }
    }

    private fun fillRechargeFields(root: AccessibilityNodeInfo): Boolean {
        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)
        if (edits.size < 2) return false

        var phoneSet = containsText(root, DriverSession.phone)
        var amountSet = containsText(root, DriverSession.amount)

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

        if (!phoneSet && setTextRobust(phoneField, DriverSession.phone)) phoneSet = true
        if (!amountSet && setTextRobust(amountField, DriverSession.amount)) amountSet = true

        return phoneSet && amountSet
    }

    private fun setTextRobust(node: AccessibilityNodeInfo, value: String): Boolean {
        if (setText(node, value)) {
            Thread.sleep(200)
            if (node.text?.toString() == value) return true
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(400)
            if (setText(node, value)) {
                Thread.sleep(200)
                if (node.text?.toString() == value) return true
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
                if (node.text?.toString()?.contains(value) == true) return true
            }
        } catch (_: Exception) { }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(400)
            return setText(node, value)
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

    private fun fillErsPin(root: AccessibilityNodeInfo): Boolean {
        val pin = SecureStore.get(this, "ers_pin")
        if (pin.isBlank()) return false

        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)

        for (node in edits) {
            val hint = node.hintText?.toString().orEmpty().lowercase()
            if (hint.contains("ers") || hint.contains("pin") || hint.contains("পিন")) {
                if (setText(node, pin)) return true
            }
        }

        // On the supplied Cockpit screen there is normally one editable field
        // after the masked "ERS PIN দিন" label.
        if (edits.size == 1) return setText(edits[0], pin)

        return false
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
