package com.reasonmachines.android

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.io.File

/**
 * JavaScript bridge for things Android WebView lacks: blob/data downloads and the Notification API.
 * Every call is ignored unless the main frame is a Reason page.
 */
class BlobBridge(private val activity: MainActivity) {

    companion object { const val NAME = "ReasonAndroid" }

    @Volatile var trustedPage = false

    private fun permissionState(): String {
        val enabled = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        return when {
            enabled && (!activity.needsNotificationRuntimePermission() || activity.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) -> "granted"
            else -> "default"
        }
    }

    @JavascriptInterface
    fun notificationPermission(): String = if (trustedPage) permissionState() else "denied"

    @JavascriptInterface
    fun requestNotificationPermission() {
        if (!trustedPage) return
        activity.runOnUiThread {
            if (activity.needsNotificationRuntimePermission() && !activity.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                activity.notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onNotificationPermissionResult(true)
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        val state = if (granted) permissionState() else "denied"
        activity.evaluateInMain("window.__reasonAndroidPermission && window.__reasonAndroidPermission('$state')")
    }

    @JavascriptInterface
    fun showNotification(title: String, body: String, tag: String, url: String) {
        if (!trustedPage) return
        Notifier.show(activity, title, body, tag, url)
    }

    @JavascriptInterface
    fun closeNotification(tag: String) {
        if (!trustedPage) return
        Notifier.cancel(activity, tag)
    }

    @JavascriptInterface
    fun saveBlob(dataUrl: String, suggestedName: String, contentDisposition: String, mimeType: String) {
        if (!trustedPage) return
        val name = suggestedName.ifBlank { URLUtil.guessFileName("blob:", contentDisposition, mimeType) }
        saveDataUrl(dataUrl, name)
    }

    @JavascriptInterface
    fun downloadFailed(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    fun saveDataUrl(dataUrl: String, rawName: String) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return
        val meta = dataUrl.substring(5, comma)
        val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = if (meta.endsWith(";base64")) Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        else java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8").toByteArray()

        var name = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
        if (!name.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name += ".$it" }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert failed")
                activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
                File(dir, name).writeBytes(bytes)
            }
            activity.runOnUiThread {
                Toast.makeText(activity, activity.getString(R.string.download_saved, name), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            downloadFailed(e.message ?: "Download failed")
        }
    }
}
