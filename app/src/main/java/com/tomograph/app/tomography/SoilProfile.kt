package com.tomograph.app.tomography

/**
 * Toprak tipine gore ses hizi (v), sogurma (Q) ve onerilen calisma
 * frekans bandini MERKEZI olarak tutan nesne.
 *
 * Buradaki v ve Q degerleri, literaturden alinan BASLANGIC varsayimlaridir.
 * Saha toprak kupu olcumlerinden gercek degerler elde edilince,
 * setCustom() ile ezilmelidir - "varsayilan" degerlerle saha calismasi
 * yapmak yanlis sonuc verebilir.
 *
 * Bu nesne, AudioChirpTest (bant secimi), ArrivalTimePicker
 * (derinlik hesabi) ve MainActivity (SIRT ray hizi baslangici) tarafindan
 * ORTAK referans olarak kullanilir - biri degisince hepsi ayni degeri gorur.
 */
object SoilProfile {

    enum class SoilType { KURU, NEMLI, DOYGUN, OZEL }

    data class Params(val soundSpeedMs: Double, val qFactor: Double,
                       val recommendedLowHz: Double, val recommendedHighHz: Double)

    private val presets = mapOf(
        SoilType.KURU to Params(soundSpeedMs = 300.0, qFactor = 10.0,
            recommendedLowHz = 250.0, recommendedHighHz = 500.0),
        SoilType.NEMLI to Params(soundSpeedMs = 700.0, qFactor = 25.0,
            recommendedLowHz = 1000.0, recommendedHighHz = 1700.0),
        SoilType.DOYGUN to Params(soundSpeedMs = 1500.0, qFactor = 60.0,
            recommendedLowHz = 3700.0, recommendedHighHz = 4200.0)
    )

    var currentType: SoilType = SoilType.DOYGUN
        private set

    var current: Params = presets[SoilType.DOYGUN]!!
        private set

    /** Hazir bir toprak tipi secer (kuru/nemli/doygun), v/Q/bant otomatik gelir. */
    fun selectPreset(type: SoilType) {
        if (type == SoilType.OZEL) return
        currentType = type
        current = presets[type]!!
    }

    /**
     * Saha olcumunden elde edilen GERCEK v ve Q degerlerini girmek icin.
     * Bu, "varsayilan" yerine saha-kalibreli calismaya gecisi saglar.
     * Bant, kullanici elle vermezse v/Q'dan kaba bir tahminle hesaplanir.
     */
    fun setCustom(soundSpeedMs: Double, qFactor: Double,
                  lowHz: Double? = null, highHz: Double? = null) {
        currentType = SoilType.OZEL
        val estimatedHigh = highHz ?: (soundSpeedMs * 2.8).coerceIn(300.0, 4200.0)
        val estimatedLow = lowHz ?: (estimatedHigh * 0.4)
        current = Params(soundSpeedMs, qFactor, estimatedLow, estimatedHigh)
    }

    fun summary(): String {
        val label = when (currentType) {
            SoilType.KURU -> "Kuru toprak"
            SoilType.NEMLI -> "Nemli toprak"
            SoilType.DOYGUN -> "Suya doygun toprak"
            SoilType.OZEL -> "Saha-kalibreli (ozel)"
        }
        return "%s: v=%.0f m/s, Q=%.0f, bant=%.0f-%.0f Hz".format(
            label, current.soundSpeedMs, current.qFactor,
            current.recommendedLowHz, current.recommendedHighHz
        )
    }
}
