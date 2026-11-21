package com.akslabs.circletosearch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.akslabs.circletosearch.utils.QrScanner
import com.akslabs.circletosearch.utils.QrResultWithBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TapQrScreen(bitmap: Bitmap?, close: () -> Unit) {
    val context = LocalContext.current
    if (bitmap == null) {
        AlertDialog(onDismissRequest = close, text = { Text("Chưa chụp được màn hình. Hãy mở lại chế độ QR.") },
            confirmButton = { TextButton(onClick = close) { Text("Đóng") } })
        return
    }
    key(bitmap) {
        var codes by remember { mutableStateOf<List<QrResultWithBounds>?>(null) }
        var tap by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        var first by remember { mutableStateOf<QrResultWithBounds?>(null) }
        LaunchedEffect(bitmap) {
            first = withContext(Dispatchers.Default) { QrScanner.scanFirstQr(bitmap) }
            codes = withContext(Dispatchers.Default) { QrScanner.scanBitmapAll(bitmap) }
        }
        Image(bitmap.asImageBitmap(), "Chạm vào mã QR", contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
                detectTapGestures { point ->
                    tap = androidx.compose.ui.geometry.Offset(point.x * bitmap.width / size.width,
                        point.y * bitmap.height / size.height)
                }
            })
        val point = tap ?: return@key
        val results = codes
        val hit = first?.takeIf { it.bounds?.contains(point.x, point.y) == true } ?: results?.firstOrNull { it.bounds?.contains(point.x, point.y) == true }
        val payload = hit?.rawText
        AlertDialog(onDismissRequest = { tap = null },
            title = { Text("Mã QR") },
            text = { Text(payload ?: if (results == null) "Đang đọc mã…" else "Không tìm thấy mã ở vị trí này.") },
            confirmButton = {
                if (payload != null) TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("QR", payload))
                    close()
                }) { Text("Sao chép") }
            },
            dismissButton = { TextButton(onClick = { tap = null }) { Text("Quay lại") } })
    }
}
