package com.tomograph.app.tomography

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Kazik koordinatlarini tutan merkezi veri kaynagi.
 *
 * KALIBRASYON NOKTASI: Her kazik icin girilen (x, y, z) koordinati, kazigin
 * TOPRAGA GIREN EN ALT UCU baz alinarak olculmelidir.
 *
 * DUZEN: 6 kazik, duzgun altigen koseleri. Kenar uzunlugu (hexEdgeMeters)
 * araziye gore 6-10 metre arasinda ayarlanabilir - degistirmek icin
 * setHexagonEdge() cagrilir, pozisyonlar otomatik yeniden hesaplanir.
 *
 * Bitisik kazik ciftlerinde yatay ara = kenar uzunlugu.
 * Karsi karsiya kazik ciftlerinde yatay ara = kenar uzunlugunun 2 kati (cap).
 *
 * z ekseni: 0 = zemin yuzeyi, negatif degerler = derinlik (asagi yon).
 * Derinlik (Z) icin sabit bir sinir YOKTUR - gercek saha olcumunde sinyalin
 * ulastigi gercek derinlik ne ise (4m, 15m, farketmez) bu deger kullanilir.
 */
object StakeCoordinates {

    const val STAKE_COUNT = 6

    // Varsayilan kenar uzunlugu - araziye gore 6.0 ile 10.0 metre arasinda
    // degistirilebilir (setHexagonEdge ile).
    var hexEdgeMeters: Double = 6.0
        private set

    // Her kazigin toprakla temas eden ucunun derinligi (genelde 0'a yakin,
    // kazik egimliyse veya zemin egimliyse kucuk farklar olabilir).
    private val stakeTipDepths = DoubleArray(STAKE_COUNT) { 0.0 }

    val positions: Array<DoubleArray> = Array(STAKE_COUNT) { DoubleArray(3) }

    init {
        recomputeHexagon()
    }

    /**
     * Altigen kenar uzunlugunu degistirir (araziye gore 6-10m arasi onerilir,
     * ama sinirlandirilmamistir - saha gercekten izin veriyorsa disina da cikilabilir).
     * Cagrildiginda, daha once elle duzeltilmis Z (derinlik) degerleri korunur,
     * sadece X/Y yeniden hesaplanir.
     */
    fun setHexagonEdge(edgeMeters: Double) {
        hexEdgeMeters = edgeMeters.coerceAtLeast(0.5)
        recomputeHexagon()
    }

    private fun recomputeHexagon() {
        val r = hexEdgeMeters // duzgun altigende kenar = cevrel yaricap
        for (k in 0 until STAKE_COUNT) {
            val angle = 2.0 * PI * k / STAKE_COUNT
            val x = r * cos(angle)
            val y = r * sin(angle)
            positions[k][0] = x
            positions[k][1] = y
            positions[k][2] = -stakeTipDepths[k]
        }
    }

    /**
     * Tek bir kazigin UCUNUN (toprakla temas noktasi) tam konumunu elle
     * girmek icin. x,y saha olcumunden, z ise genelde 0'a yakin bir
     * yuzey sapmasidir (kazik dik degilse veya zemin egimliyse).
     */
    fun updatePosition(index: Int, x: Double, y: Double, z: Double) {
        if (index in positions.indices) {
            positions[index][0] = x
            positions[index][1] = y
            positions[index][2] = z
            stakeTipDepths[index] = -z
        }
    }

    /** Yatay yayilimin genislik tahmini - gorsellestirme/izgara boyutlamasi icin. */
    fun horizontalSpanMeters(): Double = hexEdgeMeters * 2.0 // cap (en uzun mesafe)

    fun summary(): String {
        val header = "Altigen kenar: %.1f m (cap: %.1f m)".format(hexEdgeMeters, horizontalSpanMeters())
        val lines = positions.mapIndexed { i, p ->
            "Kazik %d: X=%.2f Y=%.2f Z=%.2f".format(i + 1, p[0], p[1], p[2])
        }.joinToString("\n")
        return "$header\n$lines"
    }
}
