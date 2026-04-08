package com.burntcones.sonostream

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * N-band parametric EQ. Manages multiple BiquadFilter instances,
 * cascades them for processing, and computes composite frequency response.
 */
class ParametricEQ(private val sampleRate: Int = 44100) {

    companion object {
        const val MAX_BANDS = 8
        private const val PREFS_NAME = "eq_settings"
        private const val PREFS_KEY_BANDS = "bands"
        private const val PREFS_KEY_BYPASS = "bypass"

        /** Logarithmically spaced frequency points for UI curve (20Hz–20kHz). */
        val UI_FREQUENCIES: FloatArray by lazy {
            val n = 200
            val logMin = kotlin.math.ln(20f)
            val logMax = kotlin.math.ln(20000f)
            FloatArray(n) { i ->
                kotlin.math.exp(logMin + (logMax - logMin) * i / (n - 1))
            }
        }
    }

    private val bands = mutableListOf<BiquadFilter>()

    // Per-channel filter instances for proper stereo processing
    private val bandsR = mutableListOf<BiquadFilter>()

    var bypass = false

    init {
        resetToDefaults()
    }

    fun resetToDefaults() {
        bands.clear()
        bandsR.clear()
        addBand(BiquadParams(80f, 0f, 0.7f, FilterType.LOW_SHELF))
        addBand(BiquadParams(400f, 0f, 1.0f, FilterType.BELL))
        addBand(BiquadParams(2500f, 0f, 1.0f, FilterType.BELL))
        addBand(BiquadParams(8000f, 0f, 0.7f, FilterType.HIGH_SHELF))
    }

    fun getBands(): List<BiquadParams> = bands.map { it.params }

    fun getBandCount(): Int = bands.size

    fun addBand(params: BiquadParams = BiquadParams()): Boolean {
        if (bands.size >= MAX_BANDS) return false
        val filterL = BiquadFilter().apply { configure(params, sampleRate) }
        val filterR = BiquadFilter().apply { configure(params, sampleRate) }
        bands.add(filterL)
        bandsR.add(filterR)
        return true
    }

    fun removeBand(index: Int): Boolean {
        if (index < 0 || index >= bands.size) return false
        bands.removeAt(index)
        bandsR.removeAt(index)
        return true
    }

    fun updateBand(index: Int, params: BiquadParams) {
        if (index < 0 || index >= bands.size) return
        bands[index].configure(params, sampleRate)
        bandsR[index].configure(params, sampleRate)
    }

    /**
     * Process interleaved stereo samples in-place.
     * For mono, pass channels=1.
     */
    fun process(samples: FloatArray, channels: Int) {
        if (bypass || bands.isEmpty()) return

        if (channels == 1) {
            for (b in bands) {
                if (!b.params.enabled) continue
                for (i in samples.indices) {
                    samples[i] = b.process(samples[i])
                }
            }
        } else {
            // Stereo: left channel uses bands[], right uses bandsR[]
            for (bi in bands.indices) {
                val bL = bands[bi]
                val bR = bandsR[bi]
                if (!bL.params.enabled) continue
                var i = 0
                while (i < samples.size - 1) {
                    samples[i] = bL.process(samples[i])
                    samples[i + 1] = bR.process(samples[i + 1])
                    i += 2
                }
            }
        }
    }

    fun reset() {
        bands.forEach { it.reset() }
        bandsR.forEach { it.reset() }
    }

    /**
     * Update sample rate (e.g., when loading a file with different rate).
     * Recalculates all filter coefficients.
     */
    fun setSampleRate(newRate: Int) {
        bands.forEachIndexed { i, b ->
            b.configure(b.params, newRate)
            bandsR[i].configure(bandsR[i].params, newRate)
        }
    }

    /**
     * Composite frequency response at a single frequency (sum of all bands in dB).
     */
    fun magnitudeAt(freqHz: Float): Float {
        if (bypass) return 0f
        return bands.filter { it.params.enabled }
            .sumOf { it.magnitudeAt(freqHz, sampleRate).toDouble() }
            .toFloat()
    }

    /**
     * Per-band magnitude at a frequency (for drawing individual band curves).
     */
    fun bandMagnitudeAt(bandIndex: Int, freqHz: Float): Float {
        if (bandIndex < 0 || bandIndex >= bands.size) return 0f
        return bands[bandIndex].magnitudeAt(freqHz, sampleRate)
    }

    /**
     * Full frequency response curve for UI (200 points, 20Hz–20kHz log scale).
     * Returns array of dB values.
     */
    fun getFrequencyResponse(): FloatArray {
        return FloatArray(UI_FREQUENCIES.size) { i ->
            magnitudeAt(UI_FREQUENCIES[i])
        }
    }

    /**
     * Hash of current settings — used as cache key for processed audio.
     */
    fun settingsHash(): String {
        val json = toJson().toString()
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(json.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
    }

    // ── Serialization ──────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("bypass", bypass)
        put("sampleRate", sampleRate)
        put("bands", JSONArray().apply {
            bands.forEach { b ->
                put(JSONObject().apply {
                    put("frequency", b.params.frequency)
                    put("gain", b.params.gainDb)
                    put("q", b.params.q)
                    put("type", b.params.type.name)
                    put("enabled", b.params.enabled)
                })
            }
        })
    }

    fun loadFromJson(json: JSONObject) {
        bypass = json.optBoolean("bypass", false)
        val arr = json.optJSONArray("bands") ?: return
        bands.clear()
        bandsR.clear()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val params = BiquadParams(
                frequency = obj.optDouble("frequency", 1000.0).toFloat(),
                gainDb = obj.optDouble("gain", 0.0).toFloat(),
                q = obj.optDouble("q", 1.0).toFloat(),
                type = try { FilterType.valueOf(obj.optString("type", "BELL")) } catch (_: Exception) { FilterType.BELL },
                enabled = obj.optBoolean("enabled", true)
            )
            addBand(params)
        }
    }

    /** Frequency response as JSON array for the UI curve endpoint. */
    fun responseToJson(): JSONObject = JSONObject().apply {
        val freqs = UI_FREQUENCIES
        val composite = getFrequencyResponse()
        put("frequencies", JSONArray().apply { freqs.forEach { put(it.toDouble()) } })
        put("composite", JSONArray().apply { composite.forEach { put(it.toDouble()) } })
        put("bands", JSONArray().apply {
            for (bi in bands.indices) {
                put(JSONArray().apply {
                    freqs.forEach { f -> put(bandMagnitudeAt(bi, f).toDouble()) }
                })
            }
        })
    }

    // ── Persistence ────────────────────────────────────────────────────

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_BANDS, toJson().toString())
            .putBoolean(PREFS_KEY_BYPASS, bypass)
            .apply()
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bypass = prefs.getBoolean(PREFS_KEY_BYPASS, false)
        val json = prefs.getString(PREFS_KEY_BANDS, null)
        if (json != null) {
            try {
                loadFromJson(JSONObject(json))
            } catch (_: Exception) {
                resetToDefaults()
            }
        }
    }
}
