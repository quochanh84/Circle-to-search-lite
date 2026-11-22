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
    private val tick = Runnable { advance() }

    fun start(url: String) {
        if (link != null) return
        link = url
        step = Step.START
        deadline = SystemClock.uptimeMillis() + 10000
        advance()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        link = null
    }

    private fun fail() {
        stop()
        Toast.makeText(service, "Đã sao chép. Chưa xác nhận được mã trong WinCare.", Toast.LENGTH_SHORT).show()
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
            val title = root.id("txt_title")?.text?.toString().orEmpty()
            val plan = root.id("edtSite") != null && root.id("imgvQr") != null
            val scanner = root.id("lnlInput")
            val field = root.id("edtQr")
            when (step) {
                Step.START -> when {
                    field != null -> step = Step.ENTRY
                    scanner != null -> step = Step.SCAN
                    plan -> step = Step.PLAN
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
        } else if (root != null && root.packageName?.toString() !in setOf("com.android.systemui", service.packageName)) {
            fail()
            return
        }
        handler.postDelayed(tick, 90L)
    }
}
