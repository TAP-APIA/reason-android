package com.reasonmachines.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub Releases of [REPO] for a newer APK, downloads it and hands it to the system installer.
 *
 * The repository is private, so the GitHub API needs a read-only token, which the user pastes in once
 * (stored encrypted on the device, never in the APK). If the repository is ever made public the
 * check also works without a token.
 */
class Updater(private val activity: Activity) {

    companion object {
        const val REPO = "TAP-APIA/reason-android"
        const val ACTION_CHECK = "com.reasonmachines.android.CHECK_UPDATE"
        const val ACTION_SETTINGS = "com.reasonmachines.android.UPDATE_SETTINGS"
        private const val API = "https://api.github.com/repos/$REPO"
        private const val AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val TOKEN_HELP_URL =
            "https://github.com/settings/personal-access-tokens/new"

        /** Compares dotted numeric versions such as "1.0.12" and "v1.0.9". */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+')
                .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(remote)
            val b = parts(local)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    private data class Release(val tag: String, val notes: String, val assetId: Long, val assetName: String, val size: Long)

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val plain: SharedPreferences = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = try {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "updater_secure", alias, activity,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    /** Set after the user is sent to "install unknown apps"; installed when they return. */
    private var pendingApk: File? = null
    private var busy = false

    private var token: String?
        get() = secure?.getString("token", null)?.takeIf { it.isNotBlank() }
        set(value) {
            secure?.edit()?.apply { if (value.isNullOrBlank()) remove("token") else putString("token", value.trim()) }?.apply()
        }

    /** Background check on launch, at most every few hours. Quiet unless there is an update. */
    fun autoCheck() {
        val last = plain.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return
        check(manual = false)
    }

    fun handleIntent(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_CHECK -> check(manual = true)
            ACTION_SETTINGS -> showTokenDialog()
            else -> return false
        }
        return true
    }

    fun onResume() {
        val apk = pendingApk ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = null
            launchInstaller(apk)
        }
    }

    fun check(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) toast(R.string.update_checking)
        val auth = token
        io.execute {
            val result = runCatching { fetchLatest(auth) }
            main.post {
                busy = false
                if (!alive()) return@post
                result.onSuccess { rel ->
                    plain.edit().putLong("last_check", System.currentTimeMillis()).apply()
                    when {
                        rel == null -> if (manual) toast(R.string.update_no_apk)
                        !isNewer(rel.tag, BuildConfig.VERSION_NAME) -> if (manual) toast(activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                        !manual && plain.getString("skipped", null) == rel.tag -> Unit
                        else -> offer(rel)
                    }
                }.onFailure { e ->
                    val code = (e as? HttpError)?.code
                    if (code == 401 || code == 403 || code == 404) {
                        // Private repository: no token, or the token cannot read it.
                        if (manual || (auth == null && !plain.getBoolean("never_ask_token", false))) {
                            showTokenDialog(if (auth == null) R.string.update_need_token else R.string.update_bad_token, offerNever = !manual)
                        }
                    } else if (manual) {
                        toast(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // GitHub API
    // -----------------------------------------------------------------------------------------

    private fun open(url: String, auth: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "ReasonAndroid/${BuildConfig.VERSION_NAME}")
            if (auth != null) setRequestProperty("Authorization", "Bearer $auth")
        }

    private fun fetchLatest(auth: String?): Release? {
        val conn = open("$API/releases/latest", auth, "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw HttpError(conn.responseCode)
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return Release(json.optString("tag_name"), json.optString("body"), a.getLong("id"), name, a.optLong("size"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads an asset through the API; the signed storage URL it redirects to must not get the token. */
    private fun download(rel: Release, auth: String?, dest: File, onProgress: (Int) -> Unit) {
        var conn = open("$API/releases/assets/${rel.assetId}", auth, "application/octet-stream")
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(next, null, "application/octet-stream")
        }
        try {
            if (conn.responseCode != 200) throw HttpError(conn.responseCode)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.size
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // -----------------------------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------------------------

    private fun offer(rel: Release) {
        val notes = rel.notes.trim().take(1500)
        val msg = activity.getString(R.string.update_available_body, BuildConfig.VERSION_NAME, rel.tag.removePrefix("v")) +
            if (notes.isNotEmpty()) "\n\n$notes" else ""
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(msg)
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(rel) }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_skip) { _, _ -> plain.edit().putString("skipped", rel.tag).apply() }
            .show()
    }

    private fun startDownload(rel: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val label = TextView(activity).apply { text = rel.assetName }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setView(box)
            .setCancelable(false)
            .show()
        val dest = File(File(activity.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }, rel.assetName)
        val auth = token
        io.execute {
            val result = runCatching {
                download(rel, auth, dest) { pct -> main.post { bar.isIndeterminate = false; bar.progress = pct } }
            }
            main.post {
                if (!alive()) return@post
                dialog.dismiss()
                result.onSuccess { install(dest) }.onFailure { e ->
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_download_failed)
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton(R.string.retry) { _, _ -> startDownload(rel) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun install(apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_allow_install_title)
                .setMessage(R.string.update_allow_install_body)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingApk = null }
                .show()
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    fun showTokenDialog(messageRes: Int = R.string.update_token_body, offerNever: Boolean = false) {
        if (!alive()) return
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val help = TextView(activity).apply {
            text = activity.getString(messageRes) + "\n\n" + activity.getString(R.string.update_token_steps, REPO, TOKEN_HELP_URL)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        val input = EditText(activity).apply {
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            if (token != null) setHint(R.string.update_token_saved)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(help)
            addView(input)
        }
        val b = AlertDialog.Builder(activity)
            .setTitle(R.string.update_token_title)
            .setView(box)
            .setPositiveButton(R.string.update_token_save) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    if (secure == null) { toast(R.string.update_token_storage_error); return@setPositiveButton }
                    token = value
                    plain.edit().remove("never_ask_token").apply()
                    check(manual = true)
                }
            }
        if (offerNever) {
            b.setNegativeButton(R.string.update_later, null)
            b.setNeutralButton(R.string.update_never) { _, _ -> plain.edit().putBoolean("never_ask_token", true).apply() }
        } else {
            b.setNegativeButton(android.R.string.cancel, null)
            if (token != null) b.setNeutralButton(R.string.update_token_clear) { _, _ -> token = null; toast(R.string.update_token_cleared) }
        }
        b.show()
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
