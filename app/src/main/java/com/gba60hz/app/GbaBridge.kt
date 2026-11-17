package com.gba60hz.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JS 调用原生的桥接层。注入名见 MainActivity.BRIDGE_NAME = "GbaNative"。
 * 所有方法都运行在 WebView 的 JavaBridge 线程：涉及 UI 的必须 post 到主线程。
 */
class GbaBridge(private val activity: Activity) {

    @JavascriptInterface
    fun toast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 震动：页面 navigator.vibrate 在部分 WebView 被裁剪，这里兜底。
     * @param pattern 毫秒数组，与 navigator.vibrate 语义一致（[等待,震,等待,震...]） */
    @JavascriptInterface
    fun vibrate(patternJson: String) {
        try {
            val pattern = parsePattern(patternJson)
            val vibrator = getVibrator()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+ 用 VibrationEffect，pattern 中奇数位为震动时长
                val timings = LongArray(pattern.size) { pattern[it].toLong() }
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timingsToLegacy(pattern), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    /** 分享文本（截图分享的降级路径之一） */
    @JavascriptInterface
    fun shareText(text: String) {
        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(intent, "分享"))
        }
    }

    /**
     * 保存 dataURL（截图）到 Downloads/GBA60Hz 并广播媒体扫描。
     * 页面注入脚本把 <a download> 的 blob 转成 dataURL 后调用本方法。
     */
    @JavascriptInterface
    fun saveData(dataUrl: String, filename: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val safeName = sanitize(filename)
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "GBA60Hz"
            ).apply { mkdirs() }
            val file = File(dir, safeName)
            FileOutputStream(file).use { it.write(bytes) }

            // 媒体扫描让文件在图库/Downloads 可见（Android 10+ 用 MediaStore 更规范，见文档）
            val mime = if (safeName.endsWith(".webp", true)) "image/webp" else "image/png"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/GBA60Hz")
                }
            }
            activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            activity.runOnUiThread {
                Toast.makeText(activity, "已保存：$safeName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveData failed", e)
            activity.runOnUiThread {
                Toast.makeText(activity, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 保留原下载监听器入口：把 blob/data URL 交给 saveData */
    fun downloadUrl(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("data:")) {
                val name = parseDisposition(contentDisposition) ?: defaultName(mimeType)
                saveData(url, name)
            }
            // blob: 已在注入脚本中转成 dataURL，这里通常不会收到；如需兜底可在 WebView 侧 fetch。
        } catch (e: Exception) {
            Log.w(TAG, "downloadUrl failed", e)
        }
    }

    private fun getVibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** 解析 navigator.vibrate 风格的 JSON 数组：[0,8,14,22] 等 */
    private fun parsePattern(json: String): IntArray {
        val s = json.trim().trim('[', ']')
        if (s.isBlank()) return intArrayOf()
        return s.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
    }

    /** 旧 API 的 pattern：保留原样，由系统按 [等待,震,等待...] 解释 */
    private fun timingsToLegacy(pattern: IntArray): LongArray = LongArray(pattern.size) { pattern[it].toLong() }

    private fun sanitize(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
        if (n.isBlank()) n = defaultName(null)
        n = n.replace(Regex("[\\r\\n\\t]"), "_")
        // 去掉路径穿越与非法字符
        n = n.replace("..", "_").replace(Regex("[<>:\"|?*]"), "_")
        if (!n.contains('.')) n += ".png"
        return n
    }

    private fun defaultName(mime: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "GBA60Hz_$ts.${if (mime == "image/webp") "webp" else "png"}"
    }

    private fun parseDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val marker = "filename="
        val i = disposition.indexOf(marker, ignoreCase = true)
        if (i < 0) return null
        var name = disposition.substring(i + marker.length).trim().trim('"')
        try {
            if (name.startsWith("UTF-8''", ignoreCase = true)) {
                name = URLDecoder.decode(name.removePrefix("UTF-8''"), "UTF-8")
            }
        } catch (_: Exception) {
        }
        return name.ifBlank { null }
    }

    companion object {
        private const val TAG = "GBA60Hz-Bridge"
    }
}
