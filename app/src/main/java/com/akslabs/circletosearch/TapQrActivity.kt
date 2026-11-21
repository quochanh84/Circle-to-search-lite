package com.akslabs.circletosearch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

class TapQrActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AlertDialog.Builder(this).setTitle("TapQR")
            .setMessage("Bật TapQR trong Trợ năng một lần. Sau đó chạm vào QR trên màn hình để sao chép link.")
            .setPositiveButton("Mở Trợ năng") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); finish() }
            .setNeutralButton("Thông tin ứng dụng") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))); finish() }
            .setNegativeButton("Đóng") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
