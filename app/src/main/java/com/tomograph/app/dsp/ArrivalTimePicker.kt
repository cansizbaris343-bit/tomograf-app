package com.tomograph.app.dsp

object ArrivalTimePicker {

    fun pick(referenceChirp: DoubleArray, received: DoubleArray, sampleRateHz: Double): ArrivalResult {
        val corr = crossCorrelate(received, referenceChirp)
        var peakIdx = 0
        var peakVal = Double.NEGATIVE_INFINITY
        for (i in corr.indices) {
            if (corr[i] > peakVal) {
                peakVal = corr[i]
                peakIdx = i
            }
        }
        val lagSamples = peakIdx - (referenceChirp.size - 1)
        val delaySeconds = lagSamples / sampleRateHz

        val mean = corr.average()
        val confidence = if (mean != 0.0) peakVal / kotlin.math.abs(mean) else 0.0

        return ArrivalResult(delaySeconds, confidence)
    }

    private fun crossCorrelate(a: DoubleArray, b: DoubleArray): DoubleArray {
        val n = a.size + b.size - 1
        val result = DoubleArray(n)
        for (lag in 0 until n) {
            var sum = 0.0
            for (i in b.indices) {
                val aIdx = lag - (b.size - 1) + i
                if (aIdx in a.indices) {
                    sum += a[aIdx] * b[i]
                }
            }
            result[lag] = sum
        }
        return result
    }
}

data class ArrivalResult(
    val delaySeconds: Double,
    val confidence: Double
)
