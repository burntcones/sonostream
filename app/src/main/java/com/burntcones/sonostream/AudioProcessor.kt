package com.burntcones.sonostream

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming audio processor: decodes audio, applies parametric EQ,
 * and streams WAV output on the fly. No temp files needed.
 */
object AudioProcessor {

    private const val TAG = "AudioProcessor"
    private const val MAX_LOG_ENTRIES = 30

    /** Visible diagnostics log — shown in debug panel. */
    private val logEntries = mutableListOf<String>()

    fun getLog(): List<String> = synchronized(logEntries) { logEntries.toList() }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        synchronized(logEntries) {
            logEntries.add("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg")
            while (logEntries.size > MAX_LOG_ENTRIES) logEntries.removeAt(0)
        }
    }

    private fun logErr(msg: String, e: Exception? = null) {
        Log.e(TAG, msg, e)
        synchronized(logEntries) {
            logEntries.add("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] ERROR: $msg${if (e != null) " — ${e.message}" else ""}")
            while (logEntries.size > MAX_LOG_ENTRIES) logEntries.removeAt(0)
        }
    }

    data class AudioInfo(
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long,
        val mime: String
    )

    /** Probe an audio file for its format info without decoding. */
    fun probe(inputPath: String): AudioInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(inputPath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return AudioInfo(
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                            format.getLong(MediaFormat.KEY_DURATION) else 0L,
                        mime = mime
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Probe failed: ${e.message}")
            null
        } finally {
            extractor.release()
        }
    }

    /** Estimate WAV file size from audio info. */
    fun estimateWavSize(info: AudioInfo): Long {
        val totalSamples = info.durationUs * info.sampleRate / 1_000_000L
        val dataSize = totalSamples * info.channels * 2  // 16-bit
        return 44 + dataSize  // WAV header + PCM data
    }

    /**
     * Stream-process audio: decode → EQ → WAV, writing directly to an OutputStream.
     * Starts producing output within milliseconds. Runs at 10-50x real-time.
     *
     * The EQ parameters are read LIVE from [liveEq] — changes made by the UI thread
     * are picked up on the next output buffer (~10ms). This means EQ adjustments
     * take effect within 2-5 seconds (Sonos network buffer drain time).
     *
     * @param inputPath   Source audio file
     * @param liveEq      Shared parametric EQ — read live for real-time parameter changes
     * @param output      Output stream (typically piped to HTTP response)
     * @param seekToUs    Seek to this position before processing (for Range requests)
     * @param skipBytes   Skip this many WAV data bytes from the start of output
     *                    (for Range requests — the header is only written if skipBytes == 0)
     */
    fun streamProcess(
        inputPath: String,
        liveEq: ParametricEQ,
        output: OutputStream,
        seekToUs: Long = 0,
        skipBytes: Long = 0
    ) {
        val fileName = java.io.File(inputPath).name
        log("Stream START: $fileName (seek=${seekToUs}us, skip=${skipBytes}b)")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            log("Extractor opened: ${extractor.trackCount} tracks")

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                logErr("No audio track in $fileName")
                return
            }

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            log("Format: $mime ${sampleRate}Hz ${channels}ch ${durationUs/1_000_000}s")

            // Local EQ with correct sample rate — syncs from liveEq on version changes
            val streamEq = ParametricEQ(sampleRate)
            var lastEqVersion = liveEq.version
            syncEqParams(streamEq, liveEq)
            log("EQ: ${streamEq.getBandCount()} bands, bypass=${streamEq.bypass}")

            // Seek if needed
            if (seekToUs > 0) {
                extractor.seekTo(seekToUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            log("Codec started: $mime")

            // Write WAV header (only for full-file requests, not range sub-requests)
            if (skipBytes == 0L) {
                val totalSamples = durationUs * sampleRate / 1_000_000L
                val dataSize = totalSamples * channels * 2
                writeWavHeaderToStream(output, sampleRate, channels, dataSize)
            }

            var bytesWritten = 0L
            var bytesToSkip = skipBytes
            var inputDone = false
            var outputDone = false
            val bufInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
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
                            codec.queueInputBuffer(idx, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    if (bufInfo.size > 0) {
                        // Check for live EQ changes before processing this buffer
                        val currentVersion = liveEq.version
                        if (currentVersion != lastEqVersion) {
                            syncEqParams(streamEq, liveEq)
                            lastEqVersion = currentVersion
                            Log.d(TAG, "Live EQ updated (v$currentVersion) mid-stream")
                        }

                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(bufInfo.offset)
                        outBuf.limit(bufInfo.offset + bufInfo.size)

                        // Decode PCM16 → float
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

                        // Handle skip (for Range requests)
                        if (bytesToSkip > 0) {
                            if (bytesToSkip >= pcmBytes.size) {
                                bytesToSkip -= pcmBytes.size
                            } else {
                                val offset = bytesToSkip.toInt()
                                output.write(pcmBytes, offset, pcmBytes.size - offset)
                                bytesWritten += pcmBytes.size - offset
                                bytesToSkip = 0
                            }
                        } else {
                            output.write(pcmBytes)
                            bytesWritten += pcmBytes.size
                        }
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            codec.stop()
            codec.release()
            output.flush()

            log("Stream DONE: $fileName — ${bytesWritten} bytes written")
        } catch (e: IOException) {
            // Client disconnected (Sonos stopped reading) — normal
            log("Stream ended (client disconnected): ${e.message}")
        } catch (e: Exception) {
            logErr("Stream FAILED: $fileName", e)
        } finally {
            extractor.release()
        }
    }

    /**
     * Sync local stream EQ from the shared live EQ.
     * Copies band parameters and recomputes coefficients at the stream's sample rate.
     * Filter delay lines are reset (may cause a tiny click — inaudible in practice).
     */
    fun syncEqParams(streamEq: ParametricEQ, liveEq: ParametricEQ) {
        val params = liveEq.getBandsSnapshot()
        streamEq.bypass = liveEq.bypass
        // Rebuild bands with correct sample rate coefficients
        val currentBands = streamEq.getBands()
        // Update existing bands, add/remove as needed
        for (i in params.indices) {
            if (i < currentBands.size) {
                streamEq.updateBand(i, params[i])
            } else {
                streamEq.addBand(params[i])
            }
        }
        // Remove extra bands
        while (streamEq.getBandCount() > params.size) {
            streamEq.removeBand(streamEq.getBandCount() - 1)
        }
    }

    private fun writeWavHeaderToStream(out: OutputStream, sampleRate: Int, channels: Int, dataSize: Long) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray())
        buf.putInt((36 + dataSize).toInt())
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataSize.toInt())

        out.write(buf.array())
    }

    // ── Cache (kept for future background processing) ──────────────────

    fun clearCache(cacheDir: File) {
        val dir = File(cacheDir, "eq-cache")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
