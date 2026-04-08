package com.burntcones.sonostream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val RC_PERMS = 100
        const val SERVER_PORT = 8077
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setBackgroundColor(0xFF0A0A0A.toInt())
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(UpdateBridge(this@MainActivity), "NativeUpdate")
        }
        setContentView(webView)

        // Acquire multicast lock so we can receive SSDP responses
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("sonostream").apply {
            setReferenceCounted(true)
            acquire()
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_PERMS)
        } else {
            startServer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_PERMS) {
            val audioGranted = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            if (audioGranted) {
                startServer()
            } else {
                Toast.makeText(this, "Storage permission is required to access music files", Toast.LENGTH_LONG).show()
                startServer()
            }
        }
    }

    private fun startServer() {
        val intent = Intent(this, StreamerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        webView.postDelayed({
            webView.loadUrl("http://127.0.0.1:$SERVER_PORT")
        }, 800)

        // Check for OTA updates in background
        checkForUpdate()
    }

    private fun checkForUpdate() {
        val currentVersionCode = try {
            packageManager.getPackageInfo(packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (_: Exception) { 0 }

        Thread {
            val update = UpdateChecker.checkForUpdate(currentVersionCode) ?: return@Thread
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Update Available — v${update.versionName}")
                    .setMessage(update.releaseNotes.ifEmpty { "A new version is available." })
                    .setPositiveButton("Update Now") { _, _ ->
                        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val apk = UpdateChecker.downloadApk(this, update.apkUrl)
                            runOnUiThread {
                                if (apk != null) UpdateChecker.installApk(this, apk)
                                else Toast.makeText(this, "Download failed. Try again later.", Toast.LENGTH_LONG).show()
                            }
                        }.start()
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        multicastLock?.release()
        super.onDestroy()
    }

    /**
     * JavaScript bridge for OTA updates.
     * Called from WebView via window.NativeUpdate.downloadAndInstall(apkUrl)
     */
    class UpdateBridge(private val activity: MainActivity) {

        @JavascriptInterface
        fun downloadAndInstall(apkUrl: String) {
            Thread {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
                }
                val apkFile = UpdateChecker.downloadApk(activity, apkUrl) { progress ->
                    activity.runOnUiThread {
                        activity.webView.evaluateJavascript(
                            "if(window._updateProgress) window._updateProgress($progress)", null
                        )
                    }
                }
                activity.runOnUiThread {
                    if (apkFile != null) {
                        UpdateChecker.installApk(activity, apkFile)
                    } else {
                        Toast.makeText(activity, "Update download failed", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }
}
