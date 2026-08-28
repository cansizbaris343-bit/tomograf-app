package com.tomograph.app.tomography

/**
 * Kazik koordinatlarini tutan merkezi veri kaynagi.
 *
 * ONEMLI - KALIBRASYON NOKTASI TANIMI:
 * Her kazik icin girilen (x, y, z) koordinati, kazigin UST GOVDESI
 * (sensorun oturdugu nokta) DEGIL, kazigin TOPRAGA GIREN EN ALT UCU
 * baz alinarak olculmelidir. Cunku akustik sinyalin zeminle temas ettigi
 * gercek nokta budur; SIRT ters-cozumunun dogrulugu bu referansin
 * tutarli olmasina baglidir.
 *
 * z ekseni: 0 = zemin yuzeyi, negatif degerler = derinlik (asagi yon).
 * x, y eksenleri: kazik 1'in ucu (0,0) kabul edilerek, digerlerinin
 * ona gore metre cinsinden yatay konumu.
 */
object StakeCoordinates {

    // Varsayilan/ornek degerler - sahada olcum yapildiginda kullanici
    // arayuzden gercek degerlerle degistirilmelidir.
    val positions: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.5, 0.3, -0.1),
        doubleArrayOf(0.9, -0.2, -0.15),
        doubleArrayOf(-0.4, -0.6, -0.05),
        doubleArrayOf(-0.7, 0.4, -0.2)
    )

    fun updatePosition(index: Int, x: Double, y: Double, z: Double) {
        if (index in positions.indices) {
            positions[index][0] = x
            positions[index][1] = y
            positions[index][2] = z
        }
    }

    fun summary(): String {
        return positions.mapIndexed { i, p ->
            "Kazik %d: X=%.2f Y=%.2f Z=%.2f".format(i + 1, p[0], p[1], p[2])
        }.joinToString("\n")
    }
}
