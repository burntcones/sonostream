package com.burntcones.sonostream

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays audio through the Android system output (built-in speaker, Bluetooth,
 * wired headphones) with real-time parametric EQ applied via AudioTrack.
 *
 * Uses MediaExtractor + MediaCodec to decode → biquad EQ → AudioTrack.
 * The EQ parameters are read live from the shared ParametricEQ instance,
 * so changes take effect within ~10ms (next audio buffer).
 */
object LocalPlayer {

    private const val TAG = "LocalPlayer"

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentVolume: Float = 1.0f
    private var currentFilePath: String = ""

    // EQ reference — set by ApiServer when it initializes
    var liveEq: ParametricEQ? = null

    // AudioTrack + decode thread state
    private var audioTrack: AudioTrack? = null
    private var decodeThread: Thread? = null
    @Volatile private var state: PlayState = PlayState.STOPPED
    @Volatile private var positionUs: Long = 0
    @Volatile private var durationUs: Long = 0
    @Volatile private var seekRequestUs: Long = -1
    @Volatile private var stopRequested = false
    @Volatile private var sampleRate: Int = 44100
    @Volatile private var channels: Int = 2

    private enum class PlayState { STOPPED, PLAYING, PAUSED }

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
            currentFilePath = filePath
            stopRequested = false
            seekRequestUs = -1
            positionUs = 0

            // Probe format
            val info = AudioProcessor.probe(filePath)
            if (info == null) {
                Log.e(TAG, "Cannot probe: $filePath")
                return false
            }
            sampleRate = info.sampleRate
            channels = info.channels
            durationUs = info.durationUs

            // Create AudioTrack
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(
                            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                            else AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufSize.coerceAtLeast(32768))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(currentVolume)
            audioTrack?.play()
            state = PlayState.PLAYING

            // Start decode + EQ thread
            decodeThread = Thread({ decodeLoop(filePath) }, "LocalEQ").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "Playing with EQ: $filePath (${sampleRate}Hz ${channels}ch)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play failed: ${e.message}", e)
            false
        }
    }

    /**
     * Decode → EQ → AudioTrack loop. Runs on a background thread.
     * Reads live EQ parameters on each buffer for real-time changes.
     */
    private fun decodeLoop(filePath: String) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = tf
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Local EQ instance at the file's sample rate
            val eq = liveEq
            val streamEq = ParametricEQ(sampleRate)
            var lastEqVersion = eq?.version ?: 0L
            if (eq != null) AudioProcessor.syncEqParams(streamEq, eq)

            var inputDone = false
            var outputDone = false
            val bufInfo = MediaCodec.BufferInfo()
            val track = audioTrack ?: return

            while (!outputDone && !stopRequested) {
                // Handle seek requests
                val seekTo = seekRequestUs
                if (seekTo >= 0) {
                    seekRequestUs = -1
                    codec.flush()
                    extractor.seekTo(seekTo, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    inputDone = false
                    positionUs = seekTo
                }

                // Handle pause
                while (state == PlayState.PAUSED && !stopRequested) {
                    Thread.sleep(50)
                }
                if (stopRequested) break

                // Feed input
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val read = extractor.readSampleData(buf, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(idx, 0, read, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read decoded output
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    if (bufInfo.size > 0) {
                        // Update position from presentation timestamp
                        if (bufInfo.presentationTimeUs > 0) {
                            positionUs = bufInfo.presentationTimeUs
                        }

                        // Check for live EQ changes
                        if (eq != null) {
                            val ver = eq.version
                            if (ver != lastEqVersion) {
                                AudioProcessor.syncEqParams(streamEq, eq)
                                lastEqVersion = ver
                            }
                        }

                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(bufInfo.offset)
                        outBuf.limit(bufInfo.offset + bufInfo.size)

                        // PCM16 → float
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numSamples = shortBuf.remaining()
                        val floats = FloatArray(numSamples)
                        for (i in 0 until numSamples) {
                            floats[i] = shortBuf.get() / 32768f
                        }

                        // Apply EQ
                        streamEq.process(floats, channels)

                        // Float → PCM16 bytes
                        val pcmBytes = ByteArray(numSamples * 2)
                        val pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until numSamples) {
                            pcmBuf.putShort((floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        }

                        // Write to AudioTrack (blocks if buffer full — natural backpressure)
                        track.write(pcmBytes, 0, pcmBytes.size)
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "Decode loop error: ${e.message}", e)
        } finally {
            extractor.release()
            if (!stopRequested) {
                // Natural end of track
                state = PlayState.STOPPED
            }
        }
    }

    fun pause(): Boolean {
        return try {
            if (state == PlayState.PLAYING) {
                state = PlayState.PAUSED
                audioTrack?.pause()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed: ${e.message}")
            false
        }
    }

    fun resume(): Boolean {
        return try {
            if (state == PlayState.PAUSED) {
                state = PlayState.PLAYING
                audioTrack?.play()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Resume failed: ${e.message}")
            false
        }
    }

    fun stop(): Boolean {
        return try {
            stopRequested = true
            state = PlayState.STOPPED
            decodeThread?.join(2000)
            decodeThread = null
            audioTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                it.release()
            }
            audioTrack = null
            positionUs = 0
            abandonAudioFocus()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed: ${e.message}")
            false
        }
    }

    fun seekTo(positionMs: Int): Boolean {
        seekRequestUs = positionMs.toLong() * 1000
        return true
    }

    fun setVolume(volume: Int): Boolean {
        currentVolume = (volume.coerceIn(0, 100)) / 100f
        audioTrack?.setVolume(currentVolume)
        return true
    }

    fun getStatus(): JSONObject {
        return JSONObject().apply {
            put("state", when (state) {
                PlayState.STOPPED -> "STOPPED"
                PlayState.PLAYING -> "PLAYING"
                PlayState.PAUSED -> "PAUSED_PLAYBACK"
            })
            put("volume", (currentVolume * 100).toInt())
            put("position", JSONObject().apply {
                val posS = (positionUs / 1_000_000).toInt()
                val durS = (durationUs / 1_000_000).toInt()
                put("position", formatTime(posS))
                put("duration", formatTime(durS))
                put("track", File(currentFilePath).nameWithoutExtension)
                put("uri", currentFilePath)
            })
        }
    }

    val isPlaying: Boolean get() = state == PlayState.PLAYING

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
