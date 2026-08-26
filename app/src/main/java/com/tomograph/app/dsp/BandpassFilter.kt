package com.tomograph.app.dsp

import kotlin.math.*

class BandpassFilter(
    sampleRateHz: Double,
    lowHz: Double = 4000.0,
    highHz: Double = 7777.0
) {
    private data class Biquad(val b0: Double, val b1: Double, val b2: Double,
                               val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }

    private val stages: List<Biquad>

    init {
        val centerHz = sqrt(lowHz * highHz)
        val bandwidth = highHz - lowHz
        val q = centerHz / bandwidth
        stages = listOf(
            designBandpassBiquad(sampleRateHz, centerHz, q),
            designBandpassBiquad(sampleRateHz, centerHz, q)
        )
    }

    private fun designBandpassBiquad(fs: Double, f0: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val b0 = alpha
        val b1 = 0.0
        val b2 = -alpha
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    fun processSample(x: Double): Double {
        var v = x
        for (s in stages) v = s.process(v)
        return v
    }

    fun processBuffer(input: DoubleArray): DoubleArray {
        val out = DoubleArray(input.size)
        for (i in input.indices) out[i] = processSample(input[i])
        return out
    }
}
