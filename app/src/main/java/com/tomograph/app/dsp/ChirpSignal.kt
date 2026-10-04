package com.tomograph.app.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * Dogrusal chirp (frekansi zamanla artan sinyal) ureten ortak fonksiyon.
 * Hem AudioChirpTest (mikrofon testi, 44100 Hz) hem de MainActivity'deki
 * canli DAQ veri isleme (donanimin ornekleme hizi) tarafindan kullanilir -
 * ikisinin de AYNI referans sinyali uretmesi, darbe sikistirma
 * (cross-correlation) islemi icin sarttir.
 */
object ChirpSignal {
    fun generate(f0Hz: Double, f1Hz: Double, durationSec: Double, sampleRateHz: Double): DoubleArray {
        val n = (durationSec * sampleRateHz).toInt().coerceAtLeast(2)
        val k = (f1Hz - f0Hz) / durationSec
        return DoubleArray(n) { i ->
            val t = i / sampleRateHz
            sin(2 * PI * (f0Hz * t + 0.5 * k * t * t))
        }
    }
}
