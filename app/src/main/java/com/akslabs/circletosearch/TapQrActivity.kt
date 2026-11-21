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
            .setMessage("Khi bật Trợ năng, TapQR chụp và xử lý màn hình các ứng dụng ngay trên thiết bị để tìm mã QR. Ảnh màn hình không được lưu hoặc gửi đi. Khi bạn chạm mã, liên kết được chép vào bộ nhớ tạm của Android. Bạn có thể tắt TapQR trong Trợ năng bất cứ lúc nào.")
            .setPositiveButton("Đồng ý và mở Trợ năng") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); finish() }
            .setNeutralButton("Thông tin ứng dụng") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))); finish() }
            .setNegativeButton("Đóng") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
