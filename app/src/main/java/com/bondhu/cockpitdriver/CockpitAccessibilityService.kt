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
    private val cockpitPackage = "retail.grameenphone.com.gpretail"

    override fun onServiceConnected() {
        super.onServiceConnected()
        DriverSession.lastMessage = "Accessibility Service চালু আছে"
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (!DriverSession.running) return
        if (event?.packageName?.toString() != cockpitPackage) return

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

    private fun drive() {
        if (!DriverSession.running) return
        val root = rootInActiveWindow ?: return

        // If the normal Cockpit login form is visible, use only the credentials
        // that the user explicitly saved in this app. Security prompts such as
        // Samsung Pass/biometrics are never bypassed.
        if (containsAny(root, listOf("আপনার ID দিয়ে লগ ইন করুন", "আপনার পাসওয়ার্ড দিন"))) {
            if (fillLogin(root)) {
                DriverSession.state = DriverState.OPENING
                DriverSession.lastMessage = "Saved Cockpit credentials দেওয়া হয়েছে"
                return
            }
        }

        when {
            containsAny(root, listOf("পাওয়ারলোড", "পাওয়ারলোড")) &&
                    containsText(root, DriverSession.phone) -> {
                if (fillAmountIfNeeded(root)) return
                DriverSession.state = DriverState.TAP_POWERLOAD
                DriverSession.lastMessage = "পাওয়ারলোড বাটন খোঁজা হচ্ছে"
                clickText(root, listOf("পাওয়ারলোড", "পাওয়ারলোড"))
            }

            containsAny(root, listOf("পরবর্তী")) &&
                    containsText(root, DriverSession.phone) -> {
                DriverSession.state = DriverState.TAP_NEXT
                DriverSession.lastMessage = "পরবর্তী চাপা হচ্ছে"
                clickText(root, listOf("পরবর্তী"))
            }

            containsAny(root, listOf("রিচার্জ নিশ্চিত করুন", "রিচার্জ নিশ্চিত করুন")) &&
                    containsText(root, DriverSession.phone) -> {
                DriverSession.state = DriverState.CONFIRMATION
                DriverSession.lastMessage = "Confirmation screen পাওয়া গেছে"
            }

            containsAny(root, listOf("ERS PIN দিন")) -> {
                if (fillErsPin(root)) {
                    DriverSession.state = DriverState.SUBMITTING
                    DriverSession.lastMessage = "Saved ERS PIN দেওয়া হয়েছে"
                    return
                }
                DriverSession.state = DriverState.WAITING_PIN
                DriverSession.lastMessage = "ERS PIN field পাওয়া গেছে, কিন্তু fill করা যায়নি"
                return
            }

            containsAny(root, listOf("সফল", "সফলভাবে", "Success", "Transaction Details")) -> {
                DriverSession.state = DriverState.SUCCESS
                DriverSession.lastMessage = "Recharge success screen পাওয়া গেছে"
                DriverSession.running = false
                showToast("Recharge Success")
            }

            containsAny(root, listOf("ব্যর্থ", "Failed", "failure", "দুঃখিত")) -> {
                DriverSession.state = DriverState.FAILED
                DriverSession.lastMessage = "Recharge failed screen পাওয়া গেছে"
                DriverSession.running = false
                showToast("Recharge Failed")
            }
        }
    }

    private fun fillAmountIfNeeded(root: AccessibilityNodeInfo): Boolean {
        // Prefer editable fields. The first field that already contains the target
        // number is left alone; the next suitable editable field gets the amount.
        val edits = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, edits)

        var phoneFound = false
        for (node in edits) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.contains(DriverSession.phone)) {
                phoneFound = true
                continue
            }
            if (phoneFound && (text.isBlank() || text == "0" || text != DriverSession.amount)) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    DriverSession.amount
                )
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    DriverSession.state = DriverState.ENTERING_AMOUNT
                    DriverSession.lastMessage = "রিচার্জের পরিমাণ বসানো হয়েছে"
                    return true
                }
            }
        }
        return false
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
        }
        return false
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
