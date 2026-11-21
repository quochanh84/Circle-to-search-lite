package com.akslabs.circletosearch

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.util.concurrent.Executors
import kotlin.math.*

class AutoQrService : AccessibilityService() {
    private data class Hit(val area: Rect, val url: String)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val marks = mutableMapOf<Hit, View>()
    private var foreground: String? = null
    private var revision = 0L
    private var busy = false
    private var active = false
    private var lastShot = 0L
    private val scan = Runnable { capture() }

    override fun onServiceConnected() {
        active = true
        schedule()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        val app = event.packageName?.toString()
        if (app != foreground || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            foreground = app
            revision++
            clearMarks()
        }
        handler.removeCallbacks(idleScan)
        schedule()
    }

    private val idleScan = Runnable { schedule() }

    private fun refreshLater() {
        handler.removeCallbacks(idleScan)
        if (available()) handler.postDelayed(idleScan, if (marks.isEmpty()) 2000L else 1200L)
    }

    private fun schedule() {
        if (active && !busy && !handler.hasCallbacks(scan))
            handler.postDelayed(scan, max(20L, lastShot + 525 - SystemClock.uptimeMillis()))
    }

    private fun available() = active && getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked

    private fun capture() {
        if (Build.VERSION.SDK_INT < 30 || !available() || busy) return
        busy = true
        val version = revision
        lastShot = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onFailure(errorCode: Int) {
                busy = false
                clearMarks()
                if (version != revision || errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) schedule()
            }
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                if (!active) { buffer.close(); return }
                worker.execute {
                    val hits = try {
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val copy = try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped?.recycle() }
                        try { copy?.let { decode(it) }.orEmpty() } finally { copy?.recycle() }
                    } catch (_: RuntimeException) { emptyList() } finally { buffer.close() }
                    handler.post {
                        busy = false
                        if (version == revision && available()) {
                            for (old in marks.keys.toList()) {
                                if (old !in hits) marks.remove(old)?.let { windows.removeViewImmediate(it) }
                            }
                            hits.filterNot { marks.containsKey(it) }.forEach { show(it) }
                            refreshLater()
                        } else if (active) schedule()
                    }
                }
            }
        })
    }

    private fun decode(image: Bitmap): List<Hit> {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        val input = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w,h,pixels)))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val results = try { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(input, hints) }
            catch (_: NotFoundException) { return emptyList() }
        return results.mapNotNull { r ->
            val url = r.text
            val uri = android.net.Uri.parse(url)
            val p = r.resultPoints
            if (uri.scheme?.lowercase() !in setOf("http","https") || uri.host.isNullOrBlank() || p.size < 3) return@mapNotNull null
            val left = p.minOf { it.x }; val top = p.minOf { it.y }
            val right = p.maxOf { it.x }; val bottom = p.maxOf { it.y }
            val pad = min(right-left,bottom-top) * .26f
            Hit(Rect(max(0,(left-pad).toInt()),max(0,(top-pad).toInt()),min(w,(right+pad).toInt()),min(h,(bottom+pad).toInt())),url)
        }
    }

    private fun show(hit: Hit) {
        val area = hit.area
        val marker = FrameLayout(this)
        marker.contentDescription = "Sao chép liên kết QR"
        marker.setOnClickListener {
            if (available()) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("QR", hit.url))
                showCopied()
            }
        }
        marker.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.performClick()
            true
        }
        val params = WindowManager.LayoutParams(area.width(), area.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = area.left
            y = area.top
        }
        windows.addView(marker, params)
        marks[hit] = marker
    }

    private var feedback: View? = null
    private val dismissFeedback = Runnable {
        feedback?.let { windows.removeViewImmediate(it) }
        feedback = null
    }

    private fun showCopied() {
        handler.removeCallbacks(dismissFeedback)
        if (feedback == null) {
            val density = resources.displayMetrics.density
            val label = TextView(this).apply {
                text = "Copied"
                setTextColor(-1)
                setPadding((14*density).toInt(), (6*density).toInt(), (14*density).toInt(), (6*density).toInt())
                background = GradientDrawable().apply { setColor(0xe6323232.toInt()); cornerRadius = 14*density }
            }
            val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (80*density).toInt() }
            windows.addView(label, params)
            feedback = label
        }
        handler.postDelayed(dismissFeedback, 350L)
    }

    private fun clearMarks() {
        marks.values.forEach { windows.removeViewImmediate(it) }
        marks.clear()
    }
    override fun onInterrupt() { revision++; handler.removeCallbacks(scan); handler.removeCallbacks(idleScan); clearMarks() }
    override fun onDestroy() {
        active = false
        handler.removeCallbacks(dismissFeedback)
        dismissFeedback.run()
        onInterrupt()
        worker.shutdown()
        super.onDestroy()
    }
}
