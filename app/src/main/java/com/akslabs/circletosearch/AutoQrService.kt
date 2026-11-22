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
        quickRefreshes = 3
        handler.removeCallbacks(idleScan)
        schedule()
    }

    private val idleScan = Runnable { schedule() }

    private var quickRefreshes = 0

    private fun refreshLater() {
        handler.removeCallbacks(idleScan)
        val delay = if (quickRefreshes > 0) {
            quickRefreshes--
            0L
        } else if (marks.isEmpty()) 1400L else 900L
        if (available()) handler.postDelayed(idleScan, delay)
    }

    private fun schedule() {
        if (active && !busy && !handler.hasCallbacks(scan))
            handler.postDelayed(scan, max(0L, lastShot + 525 - SystemClock.uptimeMillis()))
    }

    private fun available() = destination == null && active && getSystemService(PowerManager::class.java).isInteractive &&
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
                        try {
                            copy?.let { frame ->
                                if (frame.width >= 1000) {
                                    val preview = Bitmap.createScaledBitmap(frame, frame.width / 2, frame.height / 2, true)
                                    val early = try { decode(preview) } finally { preview.recycle() }
                                    val sx = frame.width.toFloat() / (frame.width / 2)
                                    val sy = frame.height.toFloat() / (frame.height / 2)
                                    if (early.isNotEmpty()) handler.post {
                                        if (version == revision && available() && marks.isEmpty()) early.forEach { hit ->
                                            val r = hit.area
                                            show(Hit(Rect((r.left*sx).toInt(), (r.top*sy).toInt(), (r.right*sx).toInt(), (r.bottom*sy).toInt()), hit.url))
                                        }
                                    }
                                }
                                decode(frame)
                            }.orEmpty()
                        } finally { copy?.recycle() }
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

    private val winCare by lazy { WinCareScan(this) }

    private var destination: FrameLayout? = null

    private fun closeDestination() {
        val panel = destination ?: return
        destination = null
        windows.removeViewImmediate(panel)
        schedule()
    }

    private fun chooseDestination(hit: Hit) {
        revision++
        clearMarks()
        val panel = FrameLayout(this)
        destination = panel
        panel.setOnClickListener { closeDestination() }
        panel.isFocusableInTouchMode = true
        panel.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK) false else {
                if (event.action == KeyEvent.ACTION_UP) closeDestination()
                true
            }
        }
        fun dp(n: Int) = (resources.displayMetrics.density * n).roundToInt()
        val buttons = listOf("One retailer", "Bán hàng").mapIndexed { index, title ->
            android.widget.Button(this).apply {
                text = title
                isAllCaps = false
                setPadding(0, 0, 0, 0)
                setTextColor(-1)
                background = GradientDrawable().apply {
                    setColor(if (index == 0) 0xff005c68.toInt() else 0xff174688.toInt())
                    cornerRadius = dp(9).toFloat()
                }
                setOnClickListener {
                    if (destination === panel) {
                        closeDestination()
                        if (index == 0) openRetailer(hit.url) else winCare.start(hit.url)
                    }
                }
                panel.addView(this, FrameLayout.LayoutParams(dp(108), dp(44)))
            }
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val origin = IntArray(2)
            panel.getLocationOnScreen(origin)
            val right = hit.area.right - origin[0] + dp(4)
            val width = (panel.width - right).coerceIn(dp(82), dp(108))
            val x = right.coerceIn(0, maxOf(0, panel.width - width))
            val gap = dp(5)
            val height = dp(44)
            val top = (hit.area.centerY() - origin[1] - height - gap / 2)
                .coerceIn(0, maxOf(0, panel.height - 2 * height - gap))
            buttons.forEachIndexed { index, button ->
                if (button.layoutParams.width != width) {
                    button.layoutParams = button.layoutParams.apply { this.width = width }
                }
                button.x = x.toFloat()
                button.y = (top + index * (height + gap)).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windows.addView(panel, params)
        panel.requestFocus()
    }

    private fun openRetailer(url: String) {
        val pkg = "com.masangroup.r1"
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .setPackage(pkg).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        val intent = if (view.resolveActivity(packageManager) != null) view
            else packageManager.getLaunchIntentForPackage(pkg)
        try {
            if (intent == null) throw android.content.ActivityNotFoundException()
            startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "Không mở được One retailer", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun show(hit: Hit) {
        val area = hit.area
        val marker = FrameLayout(this)
        marker.contentDescription = "Sao chép và mở liên kết QR"
        marker.setOnClickListener {
            if (available()) {
                val uri = android.net.Uri.parse(hit.url)
                val storeScan = uri.host.equals("r1.masandistribution.vn", true) &&
                    !uri.getQueryParameter("outlet").isNullOrBlank()
                val clip = ClipData.newPlainText("QR", hit.url).apply {
                    description.extras = PersistableBundle().apply {
                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                showCopied()
                if (storeScan) {
                    chooseDestination(hit)
                    return@setOnClickListener
                }
                val open = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(hit.url))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(open)
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(this, "Đã sao chép. Không có ứng dụng mở link.", android.widget.Toast.LENGTH_SHORT).show()
                }
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

    private var feedback: TextView? = null
    private val dismissFeedback = Runnable {
        feedback?.let { windows.removeViewImmediate(it) }
        feedback = null
    }

    private fun hideAfterDraw(label: TextView) {
        label.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                label.viewTreeObserver.removeOnPreDrawListener(this)
                if (feedback === label) {
                    handler.removeCallbacks(dismissFeedback)
                    handler.postDelayed(dismissFeedback, 600L)
                }
                return true
            }
        })
        label.invalidate()
    }

    private fun showCopied() {
        handler.removeCallbacks(dismissFeedback)
        if (feedback == null) {
            val density = resources.displayMetrics.density
            val label = TextView(this).apply {
                text = "Copied"
                textSize = 18f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(-1)
                setPadding((14*density).toInt(), (6*density).toInt(), (14*density).toInt(), (6*density).toInt())
                background = GradientDrawable().apply { setColor(0xe6323232.toInt()); cornerRadius = 14*density }
            }
            val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (64 * density).toInt()
                windowAnimations = 0
            }
            windows.addView(label, params)
            feedback = label
        }
        feedback?.let(::hideAfterDraw)
    }

    private fun clearMarks() {
        marks.values.forEach { windows.removeViewImmediate(it) }
        marks.clear()
    }
    override fun onInterrupt() { closeDestination(); winCare.stop(); revision++; handler.removeCallbacks(scan); handler.removeCallbacks(idleScan); clearMarks() }
    override fun onDestroy() {
        active = false
        handler.removeCallbacks(dismissFeedback)
        dismissFeedback.run()
        onInterrupt()
        worker.shutdown()
        super.onDestroy()
    }
}
