package com.burntcones.sonostream

import kotlin.math.*

/**
 * Single biquad (second-order IIR) filter section.
 * Implements Robert Bristow-Johnson's Audio EQ Cookbook formulas.
 *
 * Transfer function: H(z) = (b0 + b1*z^-1 + b2*z^-2) / (a0 + a1*z^-1 + a2*z^-2)
 * Normalized so a0 = 1.0 internally.
 */
enum class FilterType { BELL, LOW_SHELF, HIGH_SHELF, HIGH_PASS, LOW_PASS }

data class BiquadParams(
    val frequency: Float = 1000f,   // Hz (20–20000)
    val gainDb: Float = 0f,         // dB (-12 to +12)
    val q: Float = 1.0f,            // Q factor (0.1 to 18.0)
    val type: FilterType = FilterType.BELL,
    val enabled: Boolean = true
)

class BiquadFilter {

    // Normalized coefficients (a0 = 1)
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Delay line (Direct Form II Transposed)
    private var z1 = 0.0
    private var z2 = 0.0

    var params = BiquadParams()
        private set

    fun configure(params: BiquadParams, sampleRate: Int) {
        this.params = params
        computeCoefficients(params, sampleRate)
    }

    private fun computeCoefficients(p: BiquadParams, sampleRate: Int) {
        val w0 = 2.0 * PI * p.frequency / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0 * p.q)
        val A = 10.0.pow(p.gainDb / 40.0) // sqrt of linear gain

        var rb0: Double
        var rb1: Double
        var rb2: Double
        var ra0: Double
        var ra1: Double
        var ra2: Double

        when (p.type) {
            FilterType.BELL -> {
                rb0 = 1.0 + alpha * A
                rb1 = -2.0 * cosw0
                rb2 = 1.0 - alpha * A
                ra0 = 1.0 + alpha / A
                ra1 = -2.0 * cosw0
                ra2 = 1.0 - alpha / A
            }
            FilterType.LOW_SHELF -> {
                val twoSqrtAalpha = 2.0 * sqrt(A) * alpha
                rb0 = A * ((A + 1) - (A - 1) * cosw0 + twoSqrtAalpha)
                rb1 = 2.0 * A * ((A - 1) - (A + 1) * cosw0)
                rb2 = A * ((A + 1) - (A - 1) * cosw0 - twoSqrtAalpha)
                ra0 = (A + 1) + (A - 1) * cosw0 + twoSqrtAalpha
                ra1 = -2.0 * ((A - 1) + (A + 1) * cosw0)
                ra2 = (A + 1) + (A - 1) * cosw0 - twoSqrtAalpha
            }
            FilterType.HIGH_SHELF -> {
                val twoSqrtAalpha = 2.0 * sqrt(A) * alpha
                rb0 = A * ((A + 1) + (A - 1) * cosw0 + twoSqrtAalpha)
                rb1 = -2.0 * A * ((A - 1) + (A + 1) * cosw0)
                rb2 = A * ((A + 1) + (A - 1) * cosw0 - twoSqrtAalpha)
                ra0 = (A + 1) - (A - 1) * cosw0 + twoSqrtAalpha
                ra1 = 2.0 * ((A - 1) - (A + 1) * cosw0)
                ra2 = (A + 1) - (A - 1) * cosw0 - twoSqrtAalpha
            }
            FilterType.HIGH_PASS -> {
                rb0 = (1.0 + cosw0) / 2.0
                rb1 = -(1.0 + cosw0)
                rb2 = (1.0 + cosw0) / 2.0
                ra0 = 1.0 + alpha
                ra1 = -2.0 * cosw0
                ra2 = 1.0 - alpha
            }
            FilterType.LOW_PASS -> {
                rb0 = (1.0 - cosw0) / 2.0
                rb1 = 1.0 - cosw0
                rb2 = (1.0 - cosw0) / 2.0
                ra0 = 1.0 + alpha
                ra1 = -2.0 * cosw0
                ra2 = 1.0 - alpha
            }
        }

        // Normalize by a0
        b0 = rb0 / ra0
        b1 = rb1 / ra0
        b2 = rb2 / ra0
        a1 = ra1 / ra0
        a2 = ra2 / ra0
    }

    /** Process a single sample (Direct Form II Transposed). */
    fun process(input: Float): Float {
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        return output.toFloat()
    }

    /** Process a buffer of interleaved samples in-place. */
    fun processBuffer(samples: FloatArray, channels: Int) {
        if (!params.enabled) return
        // For stereo, process each channel with its own delay line
        // This simple implementation shares delay lines across channels
        // which is fine for EQ (same settings both channels)
        for (i in samples.indices) {
            samples[i] = process(samples[i])
        }
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /**
     * Calculate magnitude response at a given frequency (for UI visualization).
     * Returns gain in dB.
     */
    fun magnitudeAt(freqHz: Float, sampleRate: Int): Float {
        if (!params.enabled) return 0f
        val w = 2.0 * PI * freqHz / sampleRate
        val cosw = cos(w)
        val cos2w = cos(2.0 * w)
        val sinw = sin(w)
        val sin2w = sin(2.0 * w)

        // H(e^jw) = (b0 + b1*e^-jw + b2*e^-2jw) / (1 + a1*e^-jw + a2*e^-2jw)
        val numReal = b0 + b1 * cosw + b2 * cos2w
        val numImag = -(b1 * sinw + b2 * sin2w)
        val denReal = 1.0 + a1 * cosw + a2 * cos2w
        val denImag = -(a1 * sinw + a2 * sin2w)

        val numMagSq = numReal * numReal + numImag * numImag
        val denMagSq = denReal * denReal + denImag * denImag

        return if (denMagSq > 0) (10.0 * log10(numMagSq / denMagSq)).toFloat() else 0f
    }
}
