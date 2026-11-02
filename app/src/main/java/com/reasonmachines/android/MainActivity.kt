package com.reasonmachines.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View

    /** A popup window (OAuth sign-in and similar) shown above the main page. */
    private var popup: WebView? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var lastBackPress = 0L

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        if (result.resultCode != RESULT_OK) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        data?.data?.let { if (uris.isEmpty()) uris += it }
        if (uris.isEmpty()) cameraUri?.let { uris += it }
        cb.onReceiveValue(uris.toTypedArray())
    }

    private val mediaPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val req = pendingWebPermission ?: return@registerForActivityResult
        pendingWebPermission = null
        val allowed = req.resources.filter { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted[Manifest.permission.CAMERA] ?: hasPermission(Manifest.permission.CAMERA)
                else -> false
            }
        }
        if (allowed.isEmpty()) req.deny() else req.grant(allowed.toTypedArray())
    }

    val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        bridge.onNotificationPermissionResult(granted)
    }

    private lateinit var bridge: BlobBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.webContainer)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<Button>(R.id.retry).setOnClickListener {
            offline.visibility = View.GONE
            webView.reload()
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        Notifier.ensureChannel(this)

        bridge = BlobBridge(this)
        webView = createWebView(isPopup = false)
        container.addView(webView)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        installDocumentStartScript(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(urlFromIntent(intent) ?: BuildConfig.START_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let {
            closePopup()
            webView.loadUrl(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        closePopup()
        webView.destroy()
        super.onDestroy()
    }

    private fun urlFromIntent(intent: Intent?): String? {
        intent?.getStringExtra(Notifier.EXTRA_URL)?.let { if (Hosts.isReason(Uri.parse(it))) return it }
        val data = intent?.data ?: return null
        if (intent.action == Intent.ACTION_VIEW && Hosts.isReason(data)) return data.toString()
        return null
    }

    // ---------------------------------------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(isPopup: Boolean): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Google and some identity providers refuse embedded WebViews; present as regular Chrome.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "") + " ReasonAndroid/" + BuildConfig.VERSION_NAME
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // The site has its own dark theme driven by prefers-color-scheme.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(bridge, BlobBridge.NAME)
        wv.webViewClient = AppWebViewClient(isPopup)
        wv.webChromeClient = AppChromeClient()
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(wv, url, userAgent, contentDisposition, mimeType)
        }
        return wv
    }

    private fun installDocumentStartScript(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, Scripts.SHIM, Hosts.TRUSTED_ORIGIN_RULES)
        }
    }

    private inner class AppWebViewClient(private val isPopup: Boolean) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                // Popup that left Reason and its sign-in providers: hand it to the browser instead.
                if (isPopup && !Hosts.isReason(uri) && !Hosts.isAuth(uri) && request.isForMainFrame && request.hasGesture()) {
                    openExternal(uri)
                    return true
                }
                return false
            }
            if (scheme == "intent") {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallback = Uri.parse(uri.toString()).getQueryParameter("browser_fallback_url")
                    if (fallback != null) view.loadUrl(fallback)
                }
                return true
            }
            if (scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "javascript") return false
            // mailto:, tel:, market:, slack:, etc.
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!isPopup) {
                bridge.trustedPage = url?.let { Hosts.isReason(Uri.parse(it)) } ?: false
                offline.visibility = View.GONE
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                url != null && Hosts.isReason(Uri.parse(url))
            ) {
                view.evaluateJavascript(Scripts.SHIM, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
            if (!isPopup) bridge.trustedPage = url?.let { Hosts.isReason(Uri.parse(it)) } ?: false
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !isPopup) offline.visibility = View.VISIBLE
        }
    }

    private inner class AppChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== webView) return
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = createWebView(isPopup = true)
            // Decide on the first real URL whether this popup stays in the app.
            child.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                    route(child, request.url)

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (url != null && url != "about:blank") route(child, Uri.parse(url))
                }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = child
            resultMsg.sendToTarget()
            return true
        }

        private fun route(child: WebView, uri: Uri): Boolean {
            if (child.tag == "routed") return false
            child.tag = "routed"
            return if (Hosts.isReason(uri) || Hosts.isAuth(uri)) {
                child.webViewClient = AppWebViewClient(isPopup = true)
                showPopup(child)
                false
            } else {
                openExternal(uri)
                child.destroy()
                true
            }
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popup) closePopup()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                if (!Hosts.isReason(request.origin)) {
                    request.deny()
                    return@runOnUiThread
                }
                val needed = mutableListOf<String>()
                request.resources.forEach {
                    if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                        needed += Manifest.permission.RECORD_AUDIO
                        needed += Manifest.permission.MODIFY_AUDIO_SETTINGS
                    }
                    if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) needed += Manifest.permission.CAMERA
                }
                if (needed.isEmpty()) {
                    request.deny()
                } else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    mediaPermissions.launch(needed.toTypedArray())
                }
            }
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            cameraUri = null

            val accept = params.acceptTypes.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
            val mimes = accept.mapNotNull { a ->
                when {
                    a.contains("/") -> a
                    a.startsWith(".") -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.drop(1))
                    else -> null
                }
            }.distinct()

            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (mimes.size == 1) mimes[0] else "*/*"
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            }

            val extras = mutableListOf<Intent>()
            val wantsImage = mimes.isEmpty() || mimes.any { it.startsWith("image/") || it == "*/*" }
            // Apps that declare CAMERA may only fire IMAGE_CAPTURE once it is granted.
            if (wantsImage && hasPermission(Manifest.permission.CAMERA)) {
                try {
                    val dir = File(cacheDir, "camera").apply { mkdirs() }
                    val photo = File.createTempFile("photo_", ".jpg", dir)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", photo)
                    cameraUri = uri
                    extras += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (_: Exception) { }
            }

            val chooser = Intent.createChooser(pick, getString(R.string.choose_file)).apply {
                if (extras.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            }
            return try {
                fileChooser.launch(chooser)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        // Fullscreen video
        private var customView: View? = null
        private var customCallback: CustomViewCallback? = null

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView?.let { onHideCustomView() }
            customView = view
            customCallback = callback
            (window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(-1, -1))
        }

        override fun onHideCustomView() {
            customView?.let { (window.decorView as ViewGroup).removeView(it) }
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Popups, navigation, downloads
    // ---------------------------------------------------------------------------------------

    private fun showPopup(child: WebView) {
        closePopup()
        popup = child
        findViewById<FrameLayout>(R.id.root).addView(child)
    }

    private fun closePopup() {
        popup?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        popup = null
    }

    private fun handleBack() {
        popup?.let {
            if (it.canGoBack()) it.goBack() else closePopup()
            return
        }
        if (offline.visibility == View.VISIBLE) offline.visibility = View.GONE
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            moveTaskToBack(true)
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) { }
    }

    private fun download(wv: WebView, url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("blob:") -> wv.evaluateJavascript(Scripts.blobDownload(url, contentDisposition, mimeType), null)
            url.startsWith("data:") -> bridge.saveDataUrl(url, URLUtil.guessFileName(url, contentDisposition, mimeType))
            else -> {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                try {
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    openExternal(Uri.parse(url))
                }
            }
        }
    }

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun evaluateInMain(js: String) = runOnUiThread { webView.evaluateJavascript(js, null) }

    fun needsNotificationRuntimePermission() = Build.VERSION.SDK_INT >= 33
}
