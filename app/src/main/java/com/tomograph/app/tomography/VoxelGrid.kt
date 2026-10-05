package com.tomograph.app.tomography

class VoxelGrid(
    val nx: Int, val ny: Int, val nz: Int,
    val originX: Double, val originY: Double, val originZ: Double,
    val voxelSizeMeters: Double,
    initialVelocity: Double = 1500.0
) {
    val velocities = DoubleArray(nx * ny * nz) { initialVelocity }

    /**
     * Her vokselden kac RAY-PATH gectigini sayar (SirtInversion tarafindan
     * doldurulur). Bu, o vokseldeki sonucun GUVENILIRLIGINI gosterir:
     * - 0 veya 1 ray gecmisse: sonuc neredeyse tamamen komsu-yumusatmadan
     *   (regularization) geliyor demektir, GERCEK OLCUM DEGIL, matematiksel
     *   tahmin. Boyle yerlerdeki "anomali" gorsel gurultu olabilir.
     * - Cok ray gecmisse: sonuc gercekten birden fazla bagimsiz olcumle
     *   dogrulanmis demektir, guvenilir.
     */
    val rayHitCount = IntArray(nx * ny * nz)

    /**
     * ILETKENLIK (EM/metal dedektoru) VERISI - OPSIYONEL, DONANIM GEREKTIRIR.
     * Akustik yontem (velocities) SADECE yogunluk/sertlik farkini olcer -
     * metal, tas ve bosluk akustik olarak birbirine cok benzer tepki
     * verebilir. Gercek metal ayrimi icin, her kaziga (veya ayri bir
     * problara) eklenecek basit bir indüktif/iletkenlik sensorunden gelen
     * veri bu diziye yazilmalidir (null kaldigi surece sistem SADECE
     * akustik veriyle calisir ve bunu acikca belirtir).
     *
     * Degerler, bagil iletkenlik olceginde (0 = yalitkan/toprak, yuksek
     * deger = metal ihtimali) olmali - gercek olcekleme, kullanilacak
     * sensorun kendi kalibrasyonuna baglidir.
     */
    var conductivity: DoubleArray? = null

    fun index(ix: Int, iy: Int, iz: Int) = (iz * ny + iy) * nx + ix

    fun voxelCenter(ix: Int, iy: Int, iz: Int): DoubleArray = doubleArrayOf(
        originX + (ix + 0.5) * voxelSizeMeters,
        originY + (iy + 0.5) * voxelSizeMeters,
        originZ + (iz + 0.5) * voxelSizeMeters
    )

    fun inBounds(ix: Int, iy: Int, iz: Int) =
        ix in 0 until nx && iy in 0 until ny && iz in 0 until nz
}
