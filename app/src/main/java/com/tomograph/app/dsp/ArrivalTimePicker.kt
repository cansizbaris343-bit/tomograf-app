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

    /**
     * Gercek olcum verisinden, sinyalin gurultu tabaninin altina dustugu
     * (yani artik anlamli bilgi tasimadigi) derinligi otomatik tespit eder.
     *
     * Yontem: Alinan sinyal, zaman pencerelerine bolunur (her pencere bir
     * derinlik dilimine karsilik gelir). Her pencerenin RMS (etkin) genligi
     * hesaplanir. Genlik, ortam gurultu tabaninin (noiseFloorMultiplier kati)
     * altina dustugu ilk noktada, o noktaya karsilik gelen derinlik
     * "tespit edilen maksimum nufuz derinligi" olarak dondurulur.
     *
     * @param received Sensorden alinan (bandpass filtrelenmis) sinyal
     * @param sampleRateHz Ornekleme hizi
     * @param soundSpeedInGround Zeminde tahmini ses hizi (m/s) - zaman ekseni
     *        derinlige cevrilirken kullanilir
     * @param noiseFloorMultiplier Gurultu tabaninin kac kati altina dusulunce
     *        "sinyal bitti" sayilacagi (varsayilan 2.0 - RMS, sinyalin son
     *        %10'luk kisminin ortalama seviyesinin 2 kati altina dusunce)
     * @return Tespit edilen derinlik (metre), ve guven skoru
     */
    fun detectPenetrationDepth(
        received: DoubleArray,
        sampleRateHz: Double,
        soundSpeedInGround: Double = 1500.0,
        noiseFloorMultiplier: Double = 2.0
    ): DepthDetectionResult {
        if (received.isEmpty()) {
            return DepthDetectionResult(0.0, 0.0, false)
        }

        val windowSize = (sampleRateHz * 0.002).toInt().coerceAtLeast(8) // ~2ms pencereler
        val windowCount = received.size / windowSize
        if (windowCount < 4) {
            return DepthDetectionResult(0.0, 0.0, false)
        }

        val rmsPerWindow = DoubleArray(windowCount)
        for (w in 0 until windowCount) {
            var sumSquares = 0.0
            val start = w * windowSize
            for (i in start until start + windowSize) {
                sumSquares += received[i] * received[i]
            }
            rmsPerWindow[w] = kotlin.math.sqrt(sumSquares / windowSize)
        }

        // Gurultu tabanini, sinyalin son %20'lik kismindaki ortalama RMS'ten tahmin et
        val tailStart = (windowCount * 0.8).toInt()
        var noiseFloor = 0.0
        var tailCount = 0
        for (w in tailStart until windowCount) {
            noiseFloor += rmsPerWindow[w]
            tailCount++
        }
        noiseFloor = if (tailCount > 0) noiseFloor / tailCount else 0.0

        val threshold = noiseFloor * noiseFloorMultiplier

        // Basindan itibaren tara, genlik esigin altina ilk dustugu pencereyi bul
        var cutoffWindow = windowCount - 1
        var found = false
        for (w in 0 until windowCount) {
            if (rmsPerWindow[w] < threshold) {
                cutoffWindow = w
                found = true
                break
            }
        }

        val cutoffTimeSeconds = (cutoffWindow * windowSize) / sampleRateHz
        // Derinlik = (hiz * zaman) / 2  (sinyal gidip geri gelir, tek yon icin /2)
        val depthMeters = (soundSpeedInGround * cutoffTimeSeconds) / 2.0

        return DepthDetectionResult(depthMeters, if (found) 1.0 else 0.3, found)
    }
}

data class ArrivalResult(
    val delaySeconds: Double,
    val confidence: Double
)

data class DepthDetectionResult(
    val depthMeters: Double,
    val confidence: Double,
    val cutoffDetected: Boolean
)
