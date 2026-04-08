package com.burntcones.sonostream

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URLDecoder

class ApiServer(
    private val context: Context,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    private val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "aiff")

    /** Shared parametric EQ instance — applies to all audio output. */
    val eq = ParametricEQ()

    /** Track which file is currently being processed (for UI feedback). */
    @Volatile var processingFile: String? = null
        private set

    init {
        LocalPlayer.init(context)
        eq.load(context)
    }

    private fun localDeviceSpeaker(): JSONObject = JSONObject().apply {
        put("name", "This Device")
        put("model", "Bluetooth / Built-in Speaker")
        put("ip", "local")
        put("port", 0)
        put("type", "local")
        put("control_url", "")
        put("rendering_url", "")
        put("location", "")
        put("uuid", "local")
        put("is_coordinator", true)
        put("group_members", org.json.JSONArray())
        put("group_id", "")
    }

    private fun getLocalIp(): String {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo.ipAddress
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        return try {
            when {
                // ── API: GET ──
                method == Method.GET && uri == "/api/speakers" -> jsonResponse(JSONObject().apply {
                    put("speakers", JSONArray().apply {
                        put(localDeviceSpeaker())
                        SonosManager.speakers.values.forEach { put(it.toJson()) }
                    })
                })

                method == Method.GET && uri == "/api/discover" -> {
                    SonosManager.discover(context)
                    jsonResponse(JSONObject().apply {
                        put("speakers", JSONArray().apply {
                            put(localDeviceSpeaker())
                            SonosManager.speakers.values.forEach { put(it.toJson()) }
                        })
                        put("count", SonosManager.speakers.size)
                        put("diagnostics", SonosManager.lastDiagnostics)
                    })
                }

                method == Method.GET && uri == "/api/debug" -> {
                    jsonResponse(JSONObject().apply {
                        put("diagnostics", SonosManager.lastDiagnostics)
                        put("speaker_count", SonosManager.speakers.size)
                        put("local_ip", getLocalIp())
                        put("speakers", org.json.JSONArray().apply {
                            SonosManager.speakers.forEach { (name, sp) ->
                                put(JSONObject().apply {
                                    put("name", name)
                                    put("ip", sp.ip)
                                    put("port", sp.port)
                                    put("controlUrl", sp.controlUrl)
                                    put("renderingUrl", sp.renderingUrl)
                                    put("uuid", sp.uuid)
                                })
                            }
                        })
                        put("soap_logs", org.json.JSONArray(SonosManager.getSoapLogs()))
                    })
                }

                method == Method.GET && uri == "/api/version" -> {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
                        pInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") pInfo.versionCode
                    jsonResponse(JSONObject().apply {
                        put("versionCode", versionCode)
                        put("versionName", pInfo.versionName)
                    })
                }

                method == Method.GET && uri == "/api/check-update" -> {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
                        pInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") pInfo.versionCode
                    val update = UpdateChecker.checkForUpdate(versionCode, context)
                    jsonResponse(JSONObject().apply {
                        put("available", update != null)
                        put("current_version", pInfo.versionName)
                        if (update != null) {
                            put("new_version", update.versionName)
                            put("apk_url", update.apkUrl)
                            put("release_notes", update.releaseNotes)
                        }
                    })
                }

                method == Method.GET && uri == "/api/eq" -> {
                    jsonResponse(eq.toJson())
                }

                method == Method.GET && uri == "/api/eq/response" -> {
                    jsonResponse(eq.responseToJson())
                }

                method == Method.GET && uri == "/api/files" -> {
                    val files = scanAudioFiles()
                    jsonResponse(JSONObject().apply {
                        put("files", files)
                        put("music_dir", "Device Music")
                    })
                }

                // ── Local playback (Bluetooth / built-in) ──
                method == Method.GET && uri == "/api/local/status" -> {
                    jsonResponse(LocalPlayer.getStatus())
                }

                method == Method.GET && uri.startsWith("/api/status/") -> {
                    val name = URLDecoder.decode(uri.removePrefix("/api/status/"), "UTF-8")
                    val sp = SonosManager.speakers[name]
                    if (sp == null) {
                        jsonResponse(JSONObject().put("error", "Speaker not found"), Response.Status.NOT_FOUND)
                    } else {
                        val state = SonosManager.getTransportInfo(sp)
                        val pos = SonosManager.getPositionInfo(sp)
                        val vol = SonosManager.getVolume(sp)
                        jsonResponse(JSONObject().apply {
                            put("state", state)
                            put("position", pos)
                            put("volume", vol)
                        })
                    }
                }

                // ── API: POST ──
                method == Method.POST -> handlePost(session, uri)

                // ── Audio serving ──
                method == Method.GET && uri.startsWith("/audio/") -> serveAudio(session, uri)

                // ── Web UI ──
                method == Method.GET && (uri == "/" || uri == "/index.html") -> serveUi()

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            jsonResponse(JSONObject().put("error", e.message), Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handlePost(session: IHTTPSession, uri: String): Response {
        // Read POST body
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val bodyStr = bodyMap["postData"] ?: ""
        val data = if (bodyStr.isNotEmpty()) JSONObject(bodyStr) else JSONObject()

        return when (uri) {
            "/api/play" -> {
                val speakerName = data.optString("speaker")
                val filePath = data.optString("file")
                val sp = SonosManager.speakers[speakerName]
                if (sp == null || filePath.isEmpty()) {
                    jsonResponse(JSONObject().put("error", "Missing speaker or file"), Response.Status.BAD_REQUEST)
                } else {
                    val localIp = getLocalIp()
                    val port = listeningPort
                    val audioUri = "http://$localIp:$port/audio/${java.net.URLEncoder.encode(filePath, "UTF-8").replace("+", "%20")}"
                    val title = File(filePath).nameWithoutExtension
                    val ok = SonosManager.playUri(sp, audioUri, title)
                    jsonResponse(JSONObject().put("success", ok))
                }
            }

            "/api/control" -> {
                val sp = SonosManager.speakers[data.optString("speaker")]
                val action = data.optString("action")
                if (sp == null || action.isEmpty()) {
                    jsonResponse(JSONObject().put("error", "Missing speaker or action"), Response.Status.BAD_REQUEST)
                } else {
                    jsonResponse(JSONObject().put("success", SonosManager.transportAction(sp, action)))
                }
            }

            "/api/volume" -> {
                val speakerName = data.optString("speaker")
                val sp = SonosManager.speakers[speakerName]
                val vol = data.optInt("volume", -1)
                if (sp == null || vol < 0) {
                    jsonResponse(JSONObject().apply {
                        put("error", "Speaker '${speakerName}' not found or bad volume ($vol)")
                        put("available_speakers", JSONArray(SonosManager.speakers.keys.toList()))
                    }, Response.Status.BAD_REQUEST)
                } else {
                    jsonResponse(JSONObject().apply {
                        put("success", SonosManager.setVolume(sp, vol))
                        put("speaker", speakerName)
                        put("volume", vol)
                    })
                }
            }

            "/api/seek" -> {
                val sp = SonosManager.speakers[data.optString("speaker")]
                val position = data.optString("position")
                if (sp == null || position.isEmpty()) {
                    jsonResponse(JSONObject().put("error", "Missing speaker or position"), Response.Status.BAD_REQUEST)
                } else {
                    jsonResponse(JSONObject().put("success", SonosManager.seek(sp, position)))
                }
            }

            // ── Local playback (Bluetooth / built-in) ──

            "/api/local/play" -> {
                val filePath = data.optString("file")
                if (filePath.isEmpty()) {
                    jsonResponse(JSONObject().put("error", "Missing file"), Response.Status.BAD_REQUEST)
                } else {
                    jsonResponse(JSONObject().put("success", LocalPlayer.play(filePath)))
                }
            }

            "/api/local/control" -> {
                val action = data.optString("action")
                val ok = when (action) {
                    "Play" -> LocalPlayer.resume()
                    "Pause" -> LocalPlayer.pause()
                    "Stop" -> LocalPlayer.stop()
                    else -> false
                }
                jsonResponse(JSONObject().put("success", ok))
            }

            "/api/local/volume" -> {
                val vol = data.optInt("volume", -1)
                if (vol < 0) {
                    jsonResponse(JSONObject().put("error", "Missing volume"), Response.Status.BAD_REQUEST)
                } else {
                    jsonResponse(JSONObject().put("success", LocalPlayer.setVolume(vol)))
                }
            }

            "/api/local/seek" -> {
                val positionMs = data.optInt("position_ms", -1)
                if (positionMs < 0) {
                    jsonResponse(JSONObject().put("error", "Missing position"), Response.Status.BAD_REQUEST)
                } else {
                    jsonResponse(JSONObject().put("success", LocalPlayer.seekTo(positionMs)))
                }
            }

            // ── EQ endpoints ──

            "/api/eq" -> {
                eq.loadFromJson(data)
                eq.save(context)
                jsonResponse(eq.toJson())
            }

            "/api/eq/bypass" -> {
                eq.bypass = data.optBoolean("bypass", false)
                eq.save(context)
                jsonResponse(JSONObject().apply {
                    put("bypass", eq.bypass)
                })
            }

            "/api/eq/reset" -> {
                eq.resetToDefaults()
                eq.save(context)
                jsonResponse(eq.toJson())
            }

            "/api/eq/band" -> {
                val action = data.optString("action")
                when (action) {
                    "add" -> {
                        val params = BiquadParams(
                            frequency = data.optDouble("frequency", 1000.0).toFloat(),
                            gainDb = data.optDouble("gain", 0.0).toFloat(),
                            q = data.optDouble("q", 1.0).toFloat(),
                            type = try { FilterType.valueOf(data.optString("type", "BELL")) } catch (_: Exception) { FilterType.BELL },
                            enabled = data.optBoolean("enabled", true)
                        )
                        val ok = eq.addBand(params)
                        eq.save(context)
                        jsonResponse(JSONObject().put("success", ok).put("eq", eq.toJson()))
                    }
                    "remove" -> {
                        val idx = data.optInt("index", -1)
                        val ok = eq.removeBand(idx)
                        eq.save(context)
                        jsonResponse(JSONObject().put("success", ok).put("eq", eq.toJson()))
                    }
                    "update" -> {
                        val idx = data.optInt("index", -1)
                        val params = BiquadParams(
                            frequency = data.optDouble("frequency", 1000.0).toFloat(),
                            gainDb = data.optDouble("gain", 0.0).toFloat(),
                            q = data.optDouble("q", 1.0).toFloat(),
                            type = try { FilterType.valueOf(data.optString("type", "BELL")) } catch (_: Exception) { FilterType.BELL },
                            enabled = data.optBoolean("enabled", true)
                        )
                        eq.updateBand(idx, params)
                        eq.save(context)
                        jsonResponse(eq.toJson())
                    }
                    else -> jsonResponse(JSONObject().put("error", "Unknown action"), Response.Status.BAD_REQUEST)
                }
            }

            "/api/eq/cache/clear" -> {
                AudioProcessor.clearCache(context.cacheDir)
                jsonResponse(JSONObject().put("success", true))
            }

            // ── Multi-speaker endpoints ──

            "/api/play-multi" -> {
                val speakerNames = data.optJSONArray("speakers") ?: JSONArray()
                val filePath = data.optString("file")
                if (speakerNames.length() == 0 || filePath.isEmpty()) {
                    jsonResponse(JSONObject().put("error", "Missing speakers or file"), Response.Status.BAD_REQUEST)
                } else {
                    val localIp = getLocalIp()
                    val port = listeningPort
                    val audioUri = "http://$localIp:$port/audio/${java.net.URLEncoder.encode(filePath, "UTF-8").replace("+", "%20")}"
                    val title = File(filePath).nameWithoutExtension
                    val results = JSONObject()
                    for (i in 0 until speakerNames.length()) {
                        val name = speakerNames.getString(i)
                        val sp = SonosManager.speakers[name]
                        results.put(name, if (sp != null) SonosManager.playUri(sp, audioUri, title) else false)
                    }
                    jsonResponse(JSONObject().put("results", results))
                }
            }

            "/api/control-multi" -> {
                val speakerNames = data.optJSONArray("speakers") ?: JSONArray()
                val action = data.optString("action")
                if (speakerNames.length() == 0 || action.isEmpty()) {
                    jsonResponse(JSONObject().put("error", "Missing speakers or action"), Response.Status.BAD_REQUEST)
                } else {
                    val results = JSONObject()
                    for (i in 0 until speakerNames.length()) {
                        val name = speakerNames.getString(i)
                        val sp = SonosManager.speakers[name]
                        results.put(name, if (sp != null) SonosManager.transportAction(sp, action) else false)
                    }
                    jsonResponse(JSONObject().put("results", results))
                }
            }

            "/api/volume-multi" -> {
                val items = data.optJSONArray("speakers") ?: JSONArray()
                val results = JSONObject()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val name = item.optString("name")
                    val vol = item.optInt("volume", -1)
                    val sp = SonosManager.speakers[name]
                    results.put(name, if (sp != null && vol >= 0) SonosManager.setVolume(sp, vol) else false)
                }
                jsonResponse(JSONObject().put("results", results))
            }

            "/api/status-multi" -> {
                val speakerNames = data.optJSONArray("speakers") ?: JSONArray()
                val results = JSONObject()
                for (i in 0 until speakerNames.length()) {
                    val name = speakerNames.getString(i)
                    val sp = SonosManager.speakers[name]
                    if (sp != null) {
                        results.put(name, JSONObject().apply {
                            put("state", SonosManager.getTransportInfo(sp))
                            put("position", SonosManager.getPositionInfo(sp))
                            put("volume", SonosManager.getVolume(sp))
                        })
                    }
                }
                jsonResponse(results)
            }

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    // ── Audio file serving with range support ───────────────────────────

    private fun serveAudio(session: IHTTPSession, uri: String): Response {
        val relPath = URLDecoder.decode(uri.removePrefix("/audio/"), "UTF-8")
        val originalFile = resolveAudioFile(relPath) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        // If EQ is active (not bypassed, has non-zero gain), serve processed file
        val file: File
        val mime: String
        if (!eq.bypass && eq.getBands().any { it.enabled && it.gainDb != 0f }) {
            val cached = AudioProcessor.getCachedPath(context.cacheDir, originalFile.absolutePath, eq)
            if (cached != null) {
                file = File(cached)
                mime = "audio/wav"
            } else {
                // Process on the fly (blocks this request until done)
                val outFile = AudioProcessor.cacheFile(context.cacheDir, originalFile.absolutePath, eq)
                processingFile = originalFile.name
                val ok = AudioProcessor.processFile(originalFile.absolutePath, eq, outFile.absolutePath)
                processingFile = null
                if (ok) {
                    file = outFile
                    mime = "audio/wav"
                } else {
                    // Fallback to original on processing failure
                    file = originalFile
                    mime = mimeForAudio(originalFile)
                }
            }
        } else {
            file = originalFile
            mime = mimeForAudio(originalFile)
        }

        val fileSize = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeParts = rangeHeader.removePrefix("bytes=").split("-")
            val start = rangeParts[0].toLongOrNull() ?: 0
            val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) rangeParts[1].toLong() else fileSize - 1
            val length = end - start + 1

            val fis = FileInputStream(file)
            fis.skip(start)

            val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, length)
            response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", length.toString())
            return response
        }

        val fis = FileInputStream(file)
        val response = newFixedLengthResponse(Response.Status.OK, mime, fis, fileSize)
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun mimeForAudio(file: File): String = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        "wma" -> "audio/x-ms-wma"
        "aiff" -> "audio/aiff"
        else -> "application/octet-stream"
    }

    // ── File scanning via MediaStore ────────────────────────────────────

    private fun scanAudioFiles(): JSONArray {
        val result = JSONArray()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,      // Full file path
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.TITLE,
        )

        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idxData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val idxName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val idxSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val idxTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            while (cursor.moveToNext()) {
                val fullPath = cursor.getString(idxData) ?: continue
                val displayName = cursor.getString(idxName) ?: ""
                val size = cursor.getLong(idxSize)
                val title = cursor.getString(idxTitle) ?: displayName

                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (ext !in audioExtensions) continue

                val file = File(fullPath)
                if (!file.exists()) continue

                // Use full path as the identifier for serving
                val dir = file.parent?.let {
                    // Show relative to common music directories
                    it.removePrefix(Environment.getExternalStorageDirectory().absolutePath + "/")
                } ?: ""

                result.put(JSONObject().apply {
                    put("path", fullPath)
                    put("name", title)
                    put("ext", ".$ext")
                    put("size", size)
                    put("dir", dir)
                })
            }
        }
        return result
    }

    private fun resolveAudioFile(path: String): File? {
        val file = File(path)
        return if (file.exists() && file.isFile) file else null
    }

    // ── Web UI from assets ──────────────────────────────────────────────

    private fun serveUi(): Response {
        return try {
            val input = context.assets.open("ui.html")
            val html = input.bufferedReader().readText()
            input.close()
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "UI not found: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK): Response {
        val resp = newFixedLengthResponse(status, "application/json", json.toString())
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }
}
