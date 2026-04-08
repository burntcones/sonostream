package com.burntcones.sonostream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for OTA updates by fetching a JSON manifest from a remote URL.
 *
 * Expected manifest format (host this as a JSON file, e.g. on GitHub Pages):
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "apkUrl": "https://example.com/sonostream-v1.1.0.apk",
 *   "releaseNotes": "Bug fixes and improvements"
 * }
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    // ── CHANGE THIS to your actual manifest URL ──
    // Examples:
    //   GitHub Releases: https://raw.githubusercontent.com/user/repo/main/update.json
    //   Any static host: https://yoursite.com/sonostream/update.json
    var manifestUrl = "https://raw.githubusercontent.com/burntcones/sonostream/main/update.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    /**
     * Find a network with validated internet access (typically cellular).
     * This bypasses bindProcessToNetwork(wifiNetwork) which routes all traffic
     * over a local WiFi network that may have no internet.
     */
    private fun findInternetNetwork(context: Context?): android.net.Network? {
        if (context == null) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not find internet network: ${e.message}")
            null
        }
    }

    /**
     * Open an HTTP connection that bypasses process-level network binding.
     * Uses a validated internet network (cellular) if available, otherwise
     * falls back to the default (process-bound) connection.
     */
    private fun openConnection(url: URL, context: Context?): HttpURLConnection {
        val internetNet = findInternetNetwork(context)
        return if (internetNet != null) {
            Log.d(TAG, "Using internet network (bypassing WiFi binding) for: $url")
            internetNet.openConnection(url) as HttpURLConnection
        } else {
            Log.d(TAG, "No separate internet network found, using default for: $url")
            url.openConnection() as HttpURLConnection
        }
    }

    /**
     * Check if an update is available. Returns UpdateInfo if newer version exists, null otherwise.
     * Must be called from a background thread.
     *
     * @param context Pass a Context so the check can route over cellular/internet
     *                even when the process is bound to a local WiFi network.
     */
    fun checkForUpdate(currentVersionCode: Int, context: Context? = null): UpdateInfo? {
        return try {
            val url = URL(manifestUrl)
            val conn = openConnection(url, context)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode != 200) {
                Log.w(TAG, "Manifest fetch failed: HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val manifest = JSONObject(json)
            val remoteCode = manifest.optInt("versionCode", 0)
            val remoteName = manifest.optString("versionName", "")
            val apkUrl = manifest.optString("apkUrl", "")
            val notes = manifest.optString("releaseNotes", "")

            Log.d(TAG, "Current: $currentVersionCode, Remote: $remoteCode ($remoteName)")

            if (remoteCode > currentVersionCode && apkUrl.isNotEmpty()) {
                UpdateInfo(remoteCode, remoteName, apkUrl, notes)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download the APK to cache and return the File. Must be called from a background thread.
     * Returns null on failure.
     *
     * @param context Used both for cache dir and to route the download over
     *                an internet-capable network.
     */
    fun downloadApk(activity: Activity, apkUrl: String, onProgress: (Int) -> Unit = {}): File? {
        return try {
            val updateDir = File(activity.cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "sonostream-update.apk")
            if (apkFile.exists()) apkFile.delete()

            Log.d(TAG, "Downloading APK from $apkUrl")
            val url = URL(apkUrl)
            val conn = openConnection(url, activity)
            conn.connectTimeout = 10000
            conn.readTimeout = 30000

            val totalSize = conn.contentLength
            var downloaded = 0

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (totalSize > 0) {
                            onProgress((downloaded * 100L / totalSize).toInt())
                        }
                    }
                }
            }
            conn.disconnect()

            Log.d(TAG, "Downloaded ${apkFile.length()} bytes")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "APK download failed: ${e.message}")
            null
        }
    }

    /**
     * Trigger the system APK installer for the downloaded file.
     */
    fun installApk(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(intent)
    }
}
