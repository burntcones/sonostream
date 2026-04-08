package com.burntcones.sonostream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Plays audio through the Android system output (built-in speaker, Bluetooth,
 * wired headphones — whatever is currently the active audio route).
 */
object LocalPlayer {

    private const val TAG = "LocalPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentVolume: Float = 1.0f
    private var currentFilePath: String = ""

    fun init(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun play(filePath: String): Boolean {
        return try {
            stop()
            val file = File(filePath)
            if (!file.exists()) {
                Log.w(TAG, "File not found: $filePath")
                return false
            }

            requestAudioFocus()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(filePath)
                setVolume(currentVolume, currentVolume)
                prepare()
                start()
            }
            currentFilePath = filePath
            Log.d(TAG, "Playing: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play failed: ${e.message}")
            false
        }
    }

    fun pause(): Boolean {
        return try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed: ${e.message}")
            false
        }
    }

    fun resume(): Boolean {
        return try {
            mediaPlayer?.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Resume failed: ${e.message}")
            false
        }
    }

    fun stop(): Boolean {
        return try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            abandonAudioFocus()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed: ${e.message}")
            false
        }
    }

    fun seekTo(positionMs: Int): Boolean {
        return try {
            mediaPlayer?.seekTo(positionMs)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed: ${e.message}")
            false
        }
    }

    fun setVolume(volume: Int): Boolean {
        currentVolume = (volume.coerceIn(0, 100)) / 100f
        mediaPlayer?.setVolume(currentVolume, currentVolume)
        return true
    }

    fun getStatus(): JSONObject {
        val mp = mediaPlayer
        return JSONObject().apply {
            put("state", when {
                mp == null -> "STOPPED"
                mp.isPlaying -> "PLAYING"
                else -> "PAUSED_PLAYBACK"
            })
            put("volume", (currentVolume * 100).toInt())
            put("position", JSONObject().apply {
                val posMs = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }
                val durMs = try { mp?.duration ?: 0 } catch (_: Exception) { 0 }
                put("position", formatTime(posMs / 1000))
                put("duration", formatTime(durMs / 1000))
                put("track", File(currentFilePath).nameWithoutExtension)
                put("uri", currentFilePath)
            })
        }
    }

    val isPlaying: Boolean get() = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
