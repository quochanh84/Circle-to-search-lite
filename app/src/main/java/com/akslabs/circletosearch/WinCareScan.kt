package com.akslabs.circletosearch

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

internal class WinCareScan(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private enum class Step { START, PLAN, SCAN, ENTRY, VERIFY }
    private var step = Step.START
    private var link: String? = null
    private var deadline = 0L
    private var awaitingWinCare = false
    private var sourceWindow: Int? = null
    private var sourceFallbackAt = 0L
    private var launchAt = 0L
    private var launchRequested = false
    private val tick = Runnable { advance() }

    fun start(url: String, qrWindow: Int?) {
        if (link != null) return
        sourceWindow = qrWindow
        sourceFallbackAt = SystemClock.uptimeMillis() + 450L
        link = url
        step = Step.START
        deadline = SystemClock.uptimeMillis() + 10000
        val owner = service.rootInActiveWindow?.packageName?.toString()
        awaitingWinCare = owner != "com.vcm.wc"
        launchRequested = false
        val transientOwner = owner == null || owner == service.packageName || owner == "com.android.systemui"
        launchAt = SystemClock.uptimeMillis() + if (transientOwner) 270L else 0L
        advance()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        link = null
    }

    private fun launchFailed() {
        stop()
        Toast.makeText(service, "Không mở được ứng dụng Bán hàng (WinCare).", Toast.LENGTH_SHORT).show()
    }

    private fun fail() {
        stop()
        val message = when (step) {
            Step.START -> "WinCare: chưa nhận diện được màn hình."
            Step.PLAN -> "WinCare: chưa tìm thấy nút QR trong kế hoạch."
            Step.SCAN -> "WinCare: chưa tìm thấy nút nhập mã."
            Step.ENTRY -> "WinCare: chưa điền được mã."
            Step.VERIFY -> "WinCare: chưa xác nhận được mã."
        }
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    private fun AccessibilityNodeInfo.id(name: String) =
        findAccessibilityNodeInfosByViewId("com.vcm.wc:id/" + name).firstOrNull { it.isVisibleToUser }

    private fun AccessibilityNodeInfo.click(): Boolean {
        var node: AccessibilityNodeInfo? = this
        while (node != null) {
            if (node.isEnabled && node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        return false
    }

    private fun advance() {
        val url = link ?: return
        if (SystemClock.uptimeMillis() >= deadline) { fail(); return }
        val root = service.rootInActiveWindow
        if (root != null && root.packageName?.toString() == "com.vcm.wc") {
            awaitingWinCare = false
            val title = root.id("txt_title")?.text?.toString().orEmpty()
            val plan = root.id("imgvQr") != null
            val scanner = root.id("lnlInput")
            val field = root.id("edtQr")
            when (step) {
                Step.START -> when {
                    field != null -> step = Step.ENTRY
                    scanner != null -> step = Step.SCAN
                    plan -> step = Step.PLAN
                    (sourceWindow != null && root.windowId == sourceWindow && SystemClock.uptimeMillis() >= sourceFallbackAt) ||
                    title.contains("Bí kíp viếng thăm", true) ||
                        root.findAccessibilityNodeInfosByText("BÍ KÍP VIẾNG THĂM").any {
                            it.isVisibleToUser && it.text?.toString()?.trim().equals("BÍ KÍP VIẾNG THĂM", true)
                        } -> if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) step = Step.PLAN
                }
                Step.PLAN -> if (plan && root.id("imgvQr")?.click() == true) step = Step.SCAN
                Step.SCAN -> if (scanner?.click() == true) step = Step.ENTRY
                Step.ENTRY -> if (field != null && field.isEditable) {
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url) }
                    if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) step = Step.VERIFY
                }
                Step.VERIFY -> {
                    val button = root.id("btnOk")
                    if (field?.text?.toString() == url && button != null && button.isEnabled &&
                        button.text?.toString()?.trim().equals("Xác nhận", true)) {
                        stop()
                        if (!button.click()) fail()
                        return
                    }
                }
            }
        } else if (awaitingWinCare && !launchRequested && SystemClock.uptimeMillis() >= launchAt) {
            launchRequested = true
            val intent = service.packageManager.getLaunchIntentForPackage("com.vcm.wc")
            try {
                if (intent == null) throw android.content.ActivityNotFoundException()
                service.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: android.content.ActivityNotFoundException) {
                launchFailed()
                return
            } catch (_: SecurityException) {
                launchFailed()
                return
            }
        } else if (!awaitingWinCare && root != null && root.packageName?.toString() !in setOf("com.android.systemui", service.packageName)) {
            fail()
            return
        }
        handler.postDelayed(tick, 90L)
    }
}
