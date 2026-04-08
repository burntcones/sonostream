package com.burntcones.sonostream

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes audio files (MP3, FLAC, M4A, WAV, OGG) to PCM,
 * applies parametric EQ, and writes the result as a WAV file.
 */
object AudioProcessor {

    private const val TAG = "AudioProcessor"

    /**
     * Process an audio file through the parametric EQ and write a WAV file.
     *
     * @param inputPath  Path to source audio file (any format MediaCodec supports)
     * @param eq         Parametric EQ instance with configured bands
     * @param outputPath Path to write the processed WAV file
     * @param onProgress Callback with progress 0–100
     * @return true if processing succeeded
     */
    fun processFile(
        inputPath: String,
        eq: ParametricEQ,
        outputPath: String,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file not found: $inputPath")
            return false
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(inputPath)

            // Find the audio track
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
                Log.e(TAG, "No audio track found in $inputPath")
                return false
            }

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            Log.d(TAG, "Decoding: $inputPath ($mime, ${sampleRate}Hz, ${channels}ch, ${duration / 1000}ms)")

            // Update EQ sample rate to match source
            eq.setSampleRate(sampleRate)
            eq.reset()

            // Create decoder
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            val fos = FileOutputStream(outputFile)

            // Write placeholder WAV header (we'll fill in sizes at the end)
            val headerBuf = ByteArray(44)
            fos.write(headerBuf)

            var totalSamplesWritten = 0L
            var inputDone = false
            var outputDone = false
            val bufInfo = MediaCodec.BufferInfo()
            val totalSamplesEstimate = if (duration > 0)
                (duration * sampleRate / 1_000_000L) * channels else 1L

            while (!outputDone) {
                // Feed input buffers
                if (!inputDone) {
                    val inputBufIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufIndex)!!
                        val bytesRead = extractor.readSampleData(inputBuffer, 0)
                        if (bytesRead < 0) {
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputBufIndex, 0, bytesRead, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output buffers
                val outputBufIndex = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outputBufIndex >= 0) {
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val outputBuffer = codec.getOutputBuffer(outputBufIndex)!!
                    outputBuffer.position(bufInfo.offset)
                    outputBuffer.limit(bufInfo.offset + bufInfo.size)

                    if (bufInfo.size > 0) {
                        // Convert PCM16 bytes to float samples
                        val shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val numSamples = shortBuf.remaining()
                        val floatSamples = FloatArray(numSamples)
                        for (i in 0 until numSamples) {
                            floatSamples[i] = shortBuf.get() / 32768f
                        }

                        // Apply EQ
                        eq.process(floatSamples, channels)

                        // Convert back to PCM16 bytes
                        val outBytes = ByteArray(numSamples * 2)
                        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until numSamples) {
                            val clamped = floatSamples[i].coerceIn(-1f, 1f)
                            outBuf.putShort((clamped * 32767f).toInt().toShort())
                        }

                        fos.write(outBytes)
                        totalSamplesWritten += numSamples

                        // Progress
                        if (totalSamplesEstimate > 0) {
                            val pct = (totalSamplesWritten * 100 / totalSamplesEstimate)
                                .coerceIn(0, 100).toInt()
                            onProgress(pct)
                        }
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)
                }
            }

            codec.stop()
            codec.release()
            fos.flush()
            fos.close()

            // Write actual WAV header
            val dataSize = totalSamplesWritten * 2  // 16-bit = 2 bytes per sample
            writeWavHeader(outputPath, sampleRate, channels, dataSize)

            Log.d(TAG, "Processed: $outputPath (${totalSamplesWritten} samples, ${dataSize} bytes)")
            onProgress(100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Processing failed: ${e.message}", e)
            false
        } finally {
            extractor.release()
        }
    }

    /**
     * Write a standard 44-byte WAV header at the start of the file.
     */
    private fun writeWavHeader(path: String, sampleRate: Int, channels: Int, dataSize: Long) {
        val raf = RandomAccessFile(path, "rw")
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        raf.seek(0)
        raf.write("RIFF".toByteArray())
        raf.writeIntLE((36 + dataSize).toInt())  // Chunk size
        raf.write("WAVE".toByteArray())

        // fmt sub-chunk
        raf.write("fmt ".toByteArray())
        raf.writeIntLE(16)                       // Sub-chunk size
        raf.writeShortLE(1)                      // PCM format
        raf.writeShortLE(channels)               // Channels
        raf.writeIntLE(sampleRate)                // Sample rate
        raf.writeIntLE(byteRate)                  // Byte rate
        raf.writeShortLE(blockAlign)              // Block align
        raf.writeShortLE(bitsPerSample)           // Bits per sample

        // data sub-chunk
        raf.write("data".toByteArray())
        raf.writeIntLE(dataSize.toInt())          // Data size

        raf.close()
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    // ── Cache Management ───────────────────────────────────────────────

    /**
     * Get the cached processed file path for a given input + EQ settings.
     * Returns null if not cached.
     */
    fun getCachedPath(cacheDir: File, inputPath: String, eq: ParametricEQ): String? {
        val cached = cacheFile(cacheDir, inputPath, eq)
        return if (cached.exists()) cached.absolutePath else null
    }

    /**
     * Get the path where a processed file would be cached.
     */
    fun cacheFile(cacheDir: File, inputPath: String, eq: ParametricEQ): File {
        val hash = eq.settingsHash()
        val nameHash = inputPath.hashCode().toUInt().toString(16)
        val dir = File(cacheDir, "eq-cache")
        dir.mkdirs()
        return File(dir, "${nameHash}_${hash}.wav")
    }

    /**
     * Clear all cached processed files.
     */
    fun clearCache(cacheDir: File) {
        val dir = File(cacheDir, "eq-cache")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "EQ cache cleared")
        }
    }

    /**
     * Get total cache size in bytes.
     */
    fun cacheSize(cacheDir: File): Long {
        val dir = File(cacheDir, "eq-cache")
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
