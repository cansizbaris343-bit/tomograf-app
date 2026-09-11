package com.tomograph.app.tomography

/**
 * Kazik koordinatlarini tutan merkezi veri kaynagi.
 *
 * KALIBRASYON NOKTASI: Her kazik icin girilen (x, y, z) koordinati, kazigin
 * TOPRAGA GIREN EN ALT UCU baz alinarak olculmelidir.
 *
 * SINIRLAR:
 * - Yatay (X, Y) koordinatlari en fazla +/-3.5 metre (toplam 7 metre genislik
 *   sinirini asmayacak sekilde) olabilir - saha alani sabit 7m x 7m kabul edilir.
 * - Derinlik (Z) icin sabit bir sinir YOKTUR - gercek saha olcumunde sinyalin
 *   ulastigi gercek derinlik ne ise (4m, 19m, farketmez) bu deger kullanilir.
 *
 * z ekseni: 0 = zemin yuzeyi, negatif degerler = derinlik (asagi yon).
 */
object StakeCoordinates {

    const val MAX_HORIZONTAL_SPAN_METERS = 7.0
    private const val MAX_HALF_SPAN = MAX_HORIZONTAL_SPAN_METERS / 2.0

    // 4 kazik, varsayilan/ornek degerler - sahada gercek koordinatlarla
    // guncellenmelidir.
    val positions: Array<DoubleArray> = arrayOf(
        doubleArrayOf(-2.0, -2.0, 0.0),
        doubleArrayOf(2.0, -2.0, -0.1),
        doubleArrayOf(2.0, 2.0, -0.15),
        doubleArrayOf(-2.0, 2.0, -0.1)
    )

    fun updatePosition(index: Int, x: Double, y: Double, z: Double) {
        if (index in positions.indices) {
            // Yatay eksenler 7 metrelik alanla sinirlanir, derinlik sinirsizdir.
            positions[index][0] = x.coerceIn(-MAX_HALF_SPAN, MAX_HALF_SPAN)
            positions[index][1] = y.coerceIn(-MAX_HALF_SPAN, MAX_HALF_SPAN)
            positions[index][2] = z
        }
    }

    fun summary(): String {
        return positions.mapIndexed { i, p ->
            "Kazik %d: X=%.2f Y=%.2f Z=%.2f".format(i + 1, p[0], p[1], p[2])
        }.joinToString("\n")
    }
}
