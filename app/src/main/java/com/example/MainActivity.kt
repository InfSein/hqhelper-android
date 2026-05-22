package com.infsein.hqhelper

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.infsein.hqhelper.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

enum class AppState {
    IDLE,
    CHECKING,
    DOWNLOADING,
    EXTRACTING
}

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null

    // UI state
    private var appState by mutableStateOf(AppState.IDLE)
    private var progress by mutableStateOf(0f)
    private var updateVersion by mutableStateOf("")
    private var currentLocalVersion by mutableStateOf("")
    private var updateUrl by mutableStateOf("")
    private var showErrorDialog by mutableStateOf<String?>(null)
    private var showUpToDatePrompt by mutableStateOf(false)
    private var showConfirmDialog by mutableStateOf(false)
    private var isAssetsReady by mutableStateOf(false)
    private var isAppUpdate by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Sync and initialize folders in background thread
        lifecycleScope.launch(Dispatchers.IO) {
            val destDir = File(filesDir, "www")
            val indexFile = File(destDir, "index.html")

            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val lastVersionCode = prefs.getLong("last_version_code", -1L)
            val currentVersionCode = try {
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(packageName, 0)
                } else {
                    packageManager.getPackageInfo(packageName, 0)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            } catch (e: Exception) {
                0L
            }

            if (currentVersionCode != lastVersionCode || !indexFile.exists()) {
                if (destDir.exists()) {
                    destDir.deleteRecursively()
                }
                copyAssetFolder(this@MainActivity, "www", destDir)
                prefs.edit().putLong("last_version_code", currentVersionCode).apply()
            }

            readLocalVersion()

            withContext(Dispatchers.Main) {
                isAssetsReady = true
                // Quietly check for updates on app open
                checkForUpdates(silentOnUpToDate = true)
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        // Keep track of our system back gesture inside the WebView
                        BackHandler(enabled = webView?.canGoBack() == true) {
                            webView?.goBack()
                        }

                        if (!isAssetsReady) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = stringResource(id = R.string.initializing_app),
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                }
                            }
                        } else {
                            // Web view display
                            WebViewScreen(
                                onWebViewCreated = { createdWebView ->
                                    webView = createdWebView
                                    createdWebView.loadUrl("https://applocal/index.html")
                                }
                            )
                        }

                        // Dialog overlays
                        UpdateOverlays()
                    }
                }
            }
        }
    }

    private fun readLocalVersion() {
        val localConfigFile = File(filesDir, "www/version.json")
        if (localConfigFile.exists()) {
            try {
                val content = localConfigFile.readText()
                val json = JSONObject(content)
                currentLocalVersion = json.optString("hqhelper", "0.0.0")
            } catch (e: Exception) {
                e.printStackTrace()
                currentLocalVersion = "0.0.0"
            }
        } else {
            currentLocalVersion = "0.0.0"
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    fun checkForUpdates(silentOnUpToDate: Boolean) {
        if (appState != AppState.IDLE) return
        appState = AppState.CHECKING

        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://hqhelper.nbb.fan/version.json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    appState = AppState.IDLE
                    if (!silentOnUpToDate) {
                        showErrorDialog = e.localizedMessage ?: "Unknown error"
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    appState = AppState.IDLE
                }
                if (!response.isSuccessful) {
                    runOnUiThread {
                        if (!silentOnUpToDate) {
                            showErrorDialog = "Server responded with code ${response.code}"
                        }
                    }
                    return
                }

                try {
                    val responseStr = response.body?.string() ?: ""
                    val json = JSONObject(responseStr)

                    // 1. Check for App update first
                    val remoteAppVersion = json.optString("android", "")
                    val currentAppVersion = getAppVersionName()
                    if (remoteAppVersion.isNotEmpty() && remoteAppVersion != currentAppVersion) {
                        runOnUiThread {
                            isAppUpdate = true
                            updateVersion = remoteAppVersion
                            val dlinkAndroid = json.optString("dlink_android", "")
                            val clientInfo = json.optJSONObject("client_info")
                            val recommProxy = clientInfo?.optString("recomm_proxy", "") ?: ""
                            updateUrl = dlinkAndroid.replace("~PROXY", recommProxy)
                            showConfirmDialog = true
                        }
                        return
                    }

                    // 2. Fallback to hqhelper update
                    val remoteVersion = json.getString("hqhelper")
                    val dlinkHqHelper = json.getString("dlink_hqhelper")

                    val clientInfo = json.optJSONObject("client_info")
                    val recommProxy = clientInfo?.optString("recomm_proxy", "") ?: ""

                    runOnUiThread {
                        readLocalVersion()

                        if (remoteVersion != currentLocalVersion) {
                            isAppUpdate = false
                            updateVersion = remoteVersion
                            var url = dlinkHqHelper.replace("~VERSION", remoteVersion)
                            url = url.replace("~PROXY", recommProxy)
                            updateUrl = url
                            showConfirmDialog = true
                        } else {
                            if (!silentOnUpToDate) {
                                showUpToDatePrompt = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        if (!silentOnUpToDate) {
                            showErrorDialog = "Failed to parse update info: ${e.localizedMessage}"
                        }
                    }
                }
            }
        })
    }

    private fun startDownload() {
        if (updateUrl.isEmpty()) return
        appState = AppState.DOWNLOADING
        progress = 0f

        val client = OkHttpClient()
        val request = Request.Builder().url(updateUrl).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    appState = AppState.IDLE
                    showErrorDialog = e.localizedMessage ?: "Network error during download"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        appState = AppState.IDLE
                        showErrorDialog = "Server returned code ${response.code} during download"
                    }
                    return
                }

                val body = response.body
                if (body == null) {
                    runOnUiThread {
                        appState = AppState.IDLE
                        showErrorDialog = "Empty download body received"
                    }
                    return
                }

                try {
                    val totalBytes = body.contentLength()
                    val suffix = if (isAppUpdate) "apk" else "zip"
                    val tempFile = File(cacheDir, "update.$suffix")

                    var bytesCopied = 0L
                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytesCopied += bytes
                                if (totalBytes > 0) {
                                    progress = bytesCopied.toFloat() / totalBytes
                                }
                                bytes = input.read(buffer)
                            }
                        }
                    }

                    if (isAppUpdate) {
                        runOnUiThread {
                            appState = AppState.IDLE
                            installApk(tempFile)
                        }
                        return
                    }

                    // Done downloading, change status to extracting
                    runOnUiThread {
                        appState = AppState.EXTRACTING
                    }

                    val destDir = File(filesDir, "www")
                    val tempExtractDir = File(cacheDir, "extracted_temp")
                    if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
                    tempExtractDir.mkdirs()

                    unzip(tempFile, tempExtractDir)

                    val webRootDir = findDirectoryContainingIndex(tempExtractDir) ?: tempExtractDir

                    if (destDir.exists()) {
                        destDir.deleteRecursively()
                    }
                    destDir.mkdirs()

                    copyDirectory(webRootDir, destDir)

                    // Write correct matching version.json in target directory
                    val localVersionJson = File(destDir, "version.json")
                    localVersionJson.writeText("{\"hqhelper\":\"$updateVersion\"}")

                    // Cleanup
                    tempFile.delete()
                    tempExtractDir.deleteRecursively()

                    runOnUiThread {
                        appState = AppState.IDLE
                        readLocalVersion()
                        Toast.makeText(this@MainActivity, getString(R.string.status_success), Toast.LENGTH_LONG).show()
                        webView?.loadUrl("https://applocal/index.html")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        appState = AppState.IDLE
                        showErrorDialog = "Failed to copy and unzip: ${e.localizedMessage}"
                    }
                }
            }
        })
    }

    @Composable
    private fun UpdateOverlays() {
        // 1. Confirm dialog
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(text = stringResource(id = R.string.update_dialog_title)) },
                text = { Text(text = stringResource(id = R.string.update_dialog_message, updateVersion)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmDialog = false
                            startDownload()
                        }
                    ) {
                        Text(text = stringResource(id = R.string.update_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showConfirmDialog = false }
                    ) {
                        Text(text = stringResource(id = R.string.update_cancel))
                    }
                }
            )
        }

        // 2. Loading / Extracting non-dismissible dialogue
        if (appState == AppState.DOWNLOADING || appState == AppState.EXTRACTING) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        text = if (appState == AppState.DOWNLOADING) {
                            stringResource(id = R.string.status_downloading, progress * 100)
                        } else {
                            stringResource(id = R.string.status_extracting)
                        }
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        if (appState == AppState.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                },
                confirmButton = {}
            )
        }

        // 3. Up to date dialog
        if (showUpToDatePrompt) {
            AlertDialog(
                onDismissRequest = { showUpToDatePrompt = false },
                title = { Text(text = stringResource(id = R.string.app_name)) },
                text = { Text(text = stringResource(id = R.string.status_already_latest)) },
                confirmButton = {
                    Button(onClick = { showUpToDatePrompt = false }) {
                        Text(text = "OK")
                    }
                }
            )
        }

        // 4. Error dialog
        showErrorDialog?.let { err ->
            AlertDialog(
                onDismissRequest = { showErrorDialog = null },
                title = { Text(text = stringResource(id = R.string.app_name)) },
                text = { Text(text = stringResource(id = R.string.status_error, err)) },
                confirmButton = {
                    Button(onClick = { showErrorDialog = null }) {
                        Text(text = "OK")
                    }
                }
            )
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorDialog = "Failed to open APK: ${e.localizedMessage}"
        }
    }
}

@Composable
fun WebViewScreen(
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                // Inject JavaScript interface
                addJavascriptInterface(AndroidAPI(context as MainActivity), "androidAPI")

                webViewClient = LocalWebViewClient(context.filesDir)
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

                onWebViewCreated(this)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

class AndroidAPI(private val activity: MainActivity) {
    @JavascriptInterface
    fun checkUpdate() {
        activity.runOnUiThread {
            activity.checkForUpdates(silentOnUpToDate = false)
        }
    }
}

class LocalWebViewClient(private val filesDir: File) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val interceptPrefix = "https://applocal/"
        if (url.startsWith(interceptPrefix)) {
            val path = url.substring(interceptPrefix.length)
            var cleanPath = path.split("?")[0].split("#")[0]
            if (cleanPath.isEmpty() || cleanPath == "/") {
                cleanPath = "index.html"
            }
            val file = File(filesDir, "www/$cleanPath")
            if (file.exists() && file.isFile) {
                try {
                    val mimeType = getMimeType(cleanPath)
                    val encoding = "UTF-8"
                    val stream = FileInputStream(file)
                    return WebResourceResponse(mimeType, encoding, stream)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Return empty 404 response to prevent resolving to network and triggering ERR_NAME_NOT_RESOLVED
            return WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }
        return super.shouldInterceptRequest(view, request)
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html", ignoreCase = true) -> "text/html"
            path.endsWith(".js", ignoreCase = true) -> "text/javascript"
            path.endsWith(".css", ignoreCase = true) -> "text/css"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".gif", ignoreCase = true) -> "image/gif"
            path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            path.endsWith(".webp", ignoreCase = true) -> "image/webp"
            path.endsWith(".woff", ignoreCase = true) -> "font/woff"
            path.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
            path.endsWith(".ttf", ignoreCase = true) -> "font/ttf"
            path.endsWith(".otf", ignoreCase = true) -> "font/otf"
            path.endsWith(".json", ignoreCase = true) -> "application/json"
            path.endsWith(".ico", ignoreCase = true) -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }
}

// Global helper methods
private fun unzip(zipFile: File, targetDirectory: File) {
    ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
        var entry = zis.nextEntry
        val buffer = ByteArray(8192)
        while (entry != null) {
            val file = File(targetDirectory, entry.name)
            if (!file.canonicalPath.startsWith(targetDirectory.canonicalPath)) {
                throw SecurityException("Malicious zip entry: ${entry.name}")
            }
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                file.outputStream().use { fos ->
                    var count: Int
                    while (zis.read(buffer).also { count = it } != -1) {
                        fos.write(buffer, 0, count)
                    }
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}

private fun copyDirectory(source: File, destination: File) {
    if (source.isDirectory) {
        if (!destination.exists()) {
            destination.mkdirs()
        }
        val children = source.list() ?: return
        for (child in children) {
            copyDirectory(File(source, child), File(destination, child))
        }
    } else {
        source.copyTo(destination, overwrite = true)
    }
}

private fun copyAssetFolder(context: Context, srcFolder: String, destFolder: File) {
    destFolder.mkdirs()
    val assets: Array<String>?
    try {
        assets = context.assets.list(srcFolder)
    } catch (e: IOException) {
        return
    }
    if (assets.isNullOrEmpty()) {
        try {
            context.assets.open(srcFolder).use { inputStream ->
                destFolder.parentFile?.mkdirs()
                destFolder.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: IOException) {
            // empty directory or error
        }
    } else {
        for (asset in assets) {
            val srcAssetPath = if (srcFolder.isEmpty()) asset else "$srcFolder/$asset"
            val destAssetFile = File(destFolder, asset)
            copyAssetFolder(context, srcAssetPath, destAssetFile)
        }
    }
}

private fun findDirectoryContainingIndex(directory: File): File? {
    if (File(directory, "index.html").exists()) {
        return directory
    }
    val files = directory.listFiles() ?: return null
    for (file in files) {
        if (file.isDirectory) {
            val found = findDirectoryContainingIndex(file)
            if (found != null) {
                return found
            }
        }
    }
    return null
}
