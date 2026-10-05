package com.tomograph.app.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tomograph.app.tomography.StakeCoordinates
import com.tomograph.app.tomography.VoxelGrid
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class Tomography3DView(context: Context) : View(context) {

    private var grid: VoxelGrid? = null

    private var rotationY = 0.5f
    private var rotationX = 0.35f
    private var zoom = 1.0f

    private var depthSliceEnabled = false
    private var depthSliceFraction = 0.5f

    private var lastX = 0f
    private var lastY = 0f

    private var selectedInfo: String? = null
    var onVoxelTapped: ((String) -> Unit)? = null

    /** Her yeni izgara geldiginde, en belirgin anomalilerin ozet metni bu callback'e gelir. */
    var onAnomaliesUpdated: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // hitCount: bu vokselden kac bagimsiz ray gectigi - dusukse (0-1) sonuc
    // guvenilmez (sadece komsu-yumusatmadan geliyor), cizimde soluklastirilir.
    private data class PointItem(val gx: Float, val gy: Float, val gz: Float, val diff: Float, val hitCount: Int)
    private var cachedPoints: List<PointItem> = emptyList()

    /** En az bu kadar ray gecmeyen vokseller "veri yok, sadece tahmin" sayilir. */
    private val MIN_RELIABLE_HITS = 2

    /**
     * Komsu-bazli anomali skoru: bir vokselin, SADECE kendi yakin komsularina
     * (26 komsu) gore ne kadar farkli oldugunu olcer - genel ortalamadan
     * degil. Bu, kucuk/lokal bir nesneyi (sikke gibi), onu cevreleyen genis
     * ve farkli yogunluktaki bir zemin icinde bile one cikarabilir; global
     * ortalamaya gore kiyaslama boyle durumlarda nesneyi "ortalamaya yakin"
     * gosterip kacirabilirdi.
     */
    private data class AnomalyPoint(
        val gx: Float, val gy: Float, val gz: Float, val score: Float, val depthMeters: Double,
        val conductivityHint: String
    )
    private var topAnomalies: List<AnomalyPoint> = emptyList()

    private var verticalExaggeration = 1.0f

    private var cosY = 1f
    private var sinY = 0f
    private var cosX = 1f
    private var sinX = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.25f, 6.0f)
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(Color.rgb(15, 15, 20))
    }

    fun updateGrid(newGrid: VoxelGrid) {
        grid = newGrid
        val horizontalDim = max(newGrid.nx, newGrid.ny).toFloat()
        val verticalDim = newGrid.nz.toFloat()
        verticalExaggeration = if (verticalDim > horizontalDim) {
            (horizontalDim / verticalDim).coerceAtLeast(0.33f)
        } else 1.0f
        rebuildPointCache(newGrid)
        computeTopAnomalies(newGrid)
        invalidate()
    }

    fun resetView() {
        rotationY = 0.5f
        rotationX = 0.35f
        zoom = 1.0f
        invalidate()
    }

    fun setDepthSliceEnabled(enabled: Boolean) {
        depthSliceEnabled = enabled
        invalidate()
    }

    fun setDepthSliceFraction(fraction: Float) {
        depthSliceFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun getDepthSliceMeters(): Double {
        val g = grid ?: return 0.0
        return depthSliceFraction * g.nz * g.voxelSizeMeters
    }

    /**
     * iz=0, izgaranin EN DERIN dilimidir (VoxelGrid.originZ = -toplamDerinlik
     * olarak kuruluyor), iz=nz-1 ise yuzeye en yakin dilimdir. Yuzeyden
     * itibaren GERCEK derinlik = toplamDerinlik - (iz+0.5)*voxelSize.
     */
    private fun trueDepthMeters(g: VoxelGrid, iz: Int): Double {
        val totalDepth = g.nz * g.voxelSizeMeters
        return totalDepth - (iz + 0.5) * g.voxelSizeMeters
    }

    private fun rebuildPointCache(g: VoxelGrid) {
        val totalVoxels = g.nx * g.ny * g.nz
        val step = when {
            totalVoxels > 60000 -> 5
            totalVoxels > 25000 -> 4
            totalVoxels > 8000 -> 3
            else -> 2
        }

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        val list = ArrayList<PointItem>()
        for (iz in 0 until g.nz step step) {
            for (iy in 0 until g.ny step step) {
                for (ix in 0 until g.nx step step) {
                    val idx = g.index(ix, iy, iz)
                    val v = g.velocities[idx]
                    val diff = ((v - avg) / avg).toFloat()
                    if (Math.abs(diff) < 0.02f) continue
                    list.add(PointItem(ix - g.nx / 2f, iy - g.ny / 2f, iz - g.nz / 2f, diff, g.rayHitCount[idx]))
                }
            }
        }
        cachedPoints = list
    }

    /** Tum izgarayi tarayip, her vokseli SADECE komsularina gore puanlar, en belirgin 6 taneyi secer. */
    private fun computeTopAnomalies(g: VoxelGrid) {
        val scored = ArrayList<AnomalyPoint>()
        for (iz in 0 until g.nz) {
            for (iy in 0 until g.ny) {
                for (ix in 0 until g.nx) {
                    var neighborSum = 0.0
                    var neighborCount = 0
                    for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val nx2 = ix + dx; val ny2 = iy + dy; val nz2 = iz + dz
                        if (g.inBounds(nx2, ny2, nz2)) {
                            neighborSum += g.velocities[g.index(nx2, ny2, nz2)]
                            neighborCount++
                        }
                    }
                    if (neighborCount == 0) continue
                    val localAvg = neighborSum / neighborCount
                    if (localAvg <= 0.0) continue

                    val idx = g.index(ix, iy, iz)

                    // GUVEN FILTRESI: az ray gecen vokseller (sadece komsu
                    // yumusatmadan uretilmis, gercek olcum degil) anomali
                    // adayi sayilmaz - bu, yanlis pozitiflerin en buyuk
                    // kaynaklarindan biridir.
                    if (g.rayHitCount[idx] < MIN_RELIABLE_HITS) continue

                    val v = g.velocities[idx]
                    val score = ((v - localAvg) / localAvg).toFloat()
                    if (abs(score) < 0.03f) continue // gurultu seviyesinde, anomali sayma

                    // ILETKENLIK IPUCU: donanimda EM/iletkenlik sensoru varsa
                    // (grid.conductivity dolu), yuksek akustik sapma +
                    // yuksek iletkenlik birlikte "metal ihtimali yuksek" der.
                    // Sensor yoksa, bunu acikca "ayirt edilemiyor" diye belirtir -
                    // akustik tek basina metal/tas/bosluk ayrimi yapamaz.
                    val cond = g.conductivity?.get(idx)
                    val hint = when {
                        cond == null -> "yogunluk farki (METAL, TAS veya BOSLUK olabilir - iletkenlik verisi yok)"
                        cond > 0.5 -> "yuksek iletkenlik + yogunluk farki - METAL IHTIMALI YUKSEK"
                        else -> "dusuk iletkenlik - muhtemelen metal DEGIL (tas/bosluk/kok)"
                    }

                    scored.add(
                        AnomalyPoint(
                            ix - g.nx / 2f, iy - g.ny / 2f, iz - g.nz / 2f,
                            score, trueDepthMeters(g, iz), hint
                        )
                    )
                }
            }
        }

        topAnomalies = scored.sortedByDescending { abs(it.score) }.take(6)

        val summary = if (topAnomalies.isEmpty()) {
            "Komsularindan belirgin sekilde farkli, yeterince ray ile dogrulanmis bir nokta bulunamadi."
        } else {
            topAnomalies.mapIndexed { i, a ->
                "#%d  derinlik %.2f m, komsularina gore sapma %+.0f%%  -  %s".format(i + 1, a.depthMeters, a.score * 100, a.conductivityHint)
            }.joinToString("\n")
        }
        onAnomaliesUpdated?.invoke(summary)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // KRITIK DUZELTME: View bir ScrollView icinde oldugu icin, iki parmakla
        // dokunma baslar baslamaz ebeveyn (ScrollView) bu olayi kendi kaydirmasi
        // icin kesip almaya calisir. Asagidaki satir bunu engelleyip dokunmanin
        // tamamini bu View'a ayirir - zoom'un calismasi icin sarttir.
        parent?.requestDisallowInterceptTouchEvent(true)

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    rotationY += dx * 0.01f
                    rotationX = (rotationX + dy * 0.01f).coerceIn(-1.4f, 1.4f)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    handleTap(event.x, event.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun baseScale(g: VoxelGrid): Float {
        val maxDim = max(g.nx, g.ny).toFloat()
        return minOf(width, height) / (maxDim * 2.4f) * zoom
    }

    private fun updateTrig() {
        cosY = cos(rotationY)
        sinY = sin(rotationY)
        cosX = cos(rotationX)
        sinX = sin(rotationX)
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val g = grid ?: return
        val centerX = width / 2f
        val centerY = height / 2f
        val scale = baseScale(g)
        updateTrig()

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        var closest: PointItem? = null
        var closestDist = Float.MAX_VALUE
        for (p in cachedPoints) {
            val (sx, sy) = project(p.gx, p.gy, p.gz, scale, centerX, centerY)
            val dist = (sx - screenX) * (sx - screenX) + (sy - screenY) * (sy - screenY)
            if (dist < closestDist) {
                closestDist = dist
                closest = p
            }
        }

        if (closest != null && closestDist < 1200f) {
            // DUZELTME: eskiden (closest.gz + nz/2f)*voxelSize kullaniliyordu,
            // bu iz=0'i (en derin dilim) yanlislikla "0 metre" (yuzey) olarak
            // etiketliyordu - yon tersti. trueDepthMeters ile duzeltildi.
            val iz = (closest!!.gz + g.nz / 2f).toInt()
            val depthMeters = trueDepthMeters(g, iz)
            val v = avg * (1 + closest.diff)
            val diffPct = closest.diff * 100
            val tip = if (closest.diff < 0) "dusuk yogunluk (bosluk ihtimali)" else "yuksek yogunluk (yogun/sert)"
            val guven = if (closest.hitCount >= MIN_RELIABLE_HITS) "%d olcumle dogrulandi".format(closest.hitCount)
                        else "DUSUK GUVEN - sadece tahmin (%d olcum)".format(closest.hitCount)
            selectedInfo = "Hiz: %.0f m/s (%.0f%% sapma) - %s\nDerinlik: %.2f m\nGuven: %s".format(v, diffPct, tip, depthMeters, guven)
            onVoxelTapped?.invoke(selectedInfo!!)
            invalidate()
        }
    }

    private fun project(px: Float, py: Float, pz: Float, scale: Float, centerX: Float, centerY: Float): Pair<Float, Float> {
        val sx = px * scale
        val sy = py * scale
        val sz = pz * scale * verticalExaggeration

        val rx = sx * cosY - sz * sinY
        var rz = sx * sinY + sz * cosY

        val ry = sy * cosX - rz * sinX
        rz = sy * sinX + rz * cosX

        val screenX = centerX + rx
        val screenY = centerY + ry - rz * 0.3f
        return Pair(screenX, screenY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = grid ?: run {
            paint.color = Color.LTGRAY
            paint.textSize = 32f
            canvas.drawText("Henuz veri yok. Test butonuna basin.", 40f, height / 2f, paint)
            return
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val scale = baseScale(g)
        updateTrig()

        drawWireframeCube(canvas, g, scale, centerX, centerY)
        drawStakeMarkers(canvas, g, scale, centerX, centerY)

        for (p in cachedPoints) {
            if (depthSliceEnabled) {
                val depthFrac = (p.gz + g.nz / 2f) / g.nz
                if (Math.abs(depthFrac - depthSliceFraction) > 0.08f) continue
            }
            val (screenX, screenY) = project(p.gx, p.gy, p.gz, scale, centerX, centerY)

            val baseColor = if (p.diff < 0) {
                Color.rgb((80 + p.diff * -300).toInt().coerceIn(0, 255), 120, 255)
            } else {
                Color.rgb(255, (200 - p.diff * 300).toInt().coerceIn(0, 200), 60)
            }
            // GUVEN -> SEFFAFLIK: az ray gecen (guvenilmez) vokseller soluk
            // cizilir, boylece gercek olcumle dogrulanmis bolgeler goze daha
            // belirgin carpar, "yumusatmadan ibaret" alanlar dikkat cekmez.
            val alpha = if (p.hitCount >= MIN_RELIABLE_HITS) 255
                        else (90 + p.hitCount * 60).coerceIn(50, 255)
            paint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

            val radius = 5f + (Math.abs(p.diff) * 18f).coerceIn(2f, 16f)
            canvas.drawCircle(screenX, screenY, radius, paint)
        }
        paint.alpha = 255

        // En belirgin anomalileri (komsu-bazli) sari halka + numara ile vurgula.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        paint.textSize = 24f
        for ((i, a) in topAnomalies.withIndex()) {
            if (depthSliceEnabled) {
                val depthFrac = (a.gz + g.nz / 2f) / g.nz
                if (Math.abs(depthFrac - depthSliceFraction) > 0.08f) continue
            }
            val (sx, sy) = project(a.gx, a.gy, a.gz, scale, centerX, centerY)
            paint.color = Color.YELLOW
            canvas.drawCircle(sx, sy, 22f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText((i + 1).toString(), sx + 26f, sy + 8f, paint)
            paint.style = Paint.Style.STROKE
        }
        paint.style = Paint.Style.FILL

        paint.color = Color.LTGRAY
        paint.textSize = 22f
        canvas.drawText("Mavi = dusuk yogunluk | Turuncu/Kirmizi = yuksek yogunluk | Sari halka = komsularindan belirgin sapma", 20f, height - 55f, paint)

        if (verticalExaggeration < 0.99f) {
            paint.color = Color.rgb(200, 180, 100)
            canvas.drawText("Not: dikey eksen okunabilirlik icin sikistirilmistir", 20f, height - 25f, paint)
        }

        if (depthSliceEnabled) {
            paint.color = Color.CYAN
            paint.textSize = 26f
            val depthMeters = depthSliceFraction * g.nz * g.voxelSizeMeters
            canvas.drawText("Kesit derinligi: %.2f m".format(depthMeters), 20f, height - 90f, paint)
        }

        selectedInfo?.let {
            paint.color = Color.YELLOW
            paint.textSize = 24f
            it.split("\n").forEachIndexed { i, line ->
                canvas.drawText(line, 20f, 40f + i * 30f, paint)
            }
        }
    }

    /**
     * Izgara (voksel kutusu) her zaman dikdortgen/kup seklinde kalir - bu
     * normaldir, duzenli bir 3D izgaranin matematiksel yapisi budur. Ama
     * GERCEK kazik konumlari (altigen duzeni) bu kutunun UZERINE, StakeCoordinates'tan
     * okunarak isaretlenir - VE aralarina cizgi cekilerek altigen seklin
     * KENDISI cizilir, boylece altigen yerlesim goze acikca gorunur.
     * Renk bilerek parlak magenta secildi - cetvel noktalarinin (teal) ve
     * veri noktalarinin (mavi/turuncu) hicbiriyle karismasin diye.
     */
    private fun drawStakeMarkers(canvas: Canvas, g: VoxelGrid, scale: Float, centerX: Float, centerY: Float) {
        val screenPoints = StakeCoordinates.positions.map { pos ->
            val ix = (pos[0] - g.originX) / g.voxelSizeMeters
            val iy = (pos[1] - g.originY) / g.voxelSizeMeters
            val iz = (pos[2] - g.originZ) / g.voxelSizeMeters
            val gx = ix.toFloat() - g.nx / 2f
            val gy = iy.toFloat() - g.ny / 2f
            val gz = iz.toFloat() - g.nz / 2f
            project(gx, gy, gz, scale, centerX, centerY)
        }

        // Altigeni OLUSTURAN cizgiler: her kazigi bir sonrakine bagla (son->ilk dahil).
        paint.color = Color.rgb(255, 0, 230)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        for (i in screenPoints.indices) {
            val a = screenPoints[i]
            val b = screenPoints[(i + 1) % screenPoints.size]
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
        }

        paint.style = Paint.Style.FILL
        paint.textSize = 28f
        for ((i, sp) in screenPoints.withIndex()) {
            paint.color = Color.rgb(255, 0, 230)
            canvas.drawCircle(sp.first, sp.second, 14f, paint)
            paint.color = Color.WHITE
            canvas.drawText("K" + (i + 1), sp.first + 16f, sp.second - 12f, paint)
        }
    }

    private fun drawWireframeCube(canvas: Canvas, g: VoxelGrid, scale: Float, centerX: Float, centerY: Float) {
        val hx = g.nx / 2f
        val hy = g.ny / 2f
        val hz = g.nz / 2f

        val corners = arrayOf(
            floatArrayOf(-hx, -hy, -hz), floatArrayOf(hx, -hy, -hz),
            floatArrayOf(hx, hy, -hz), floatArrayOf(-hx, hy, -hz),
            floatArrayOf(-hx, -hy, hz), floatArrayOf(hx, -hy, hz),
            floatArrayOf(hx, hy, hz), floatArrayOf(-hx, hy, hz)
        )
        val projected = corners.map { project(it[0], it[1], it[2], scale, centerX, centerY) }

        val edges = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7
        )

        paint.color = Color.rgb(70, 70, 85)
        paint.strokeWidth = 2.5f
        for ((a, b) in edges) {
            canvas.drawLine(projected[a].first, projected[a].second, projected[b].first, projected[b].second, paint)
        }

        drawEdgeRuler(canvas, corners[0], corners[1], g.nx * g.voxelSizeMeters, scale, centerX, centerY, "Genislik X")
        drawEdgeRuler(canvas, corners[0], corners[3], g.ny * g.voxelSizeMeters, scale, centerX, centerY, "Genislik Y")
        // DUZELTME: corners[0] (iz=0) EN DERIN nokta, corners[4] (iz=nz) ise
        // YUZEYE yakin nokta - eskiden cetvel bunu ters etiketliyordu (yuzeyi
        // "buyuk derinlik", en derin noktayi "0m" gosteriyordu). Baslangic/bitis
        // corners[4]->corners[0] olarak degistirildi: yuzey artik "0,0m",
        // en derin nokta gercek toplam derinligi gosteriyor.
        drawEdgeRuler(canvas, corners[4], corners[0], g.nz * g.voxelSizeMeters, scale, centerX, centerY, "Derinlik")
    }

    private fun drawEdgeRuler(
        canvas: Canvas, from: FloatArray, to: FloatArray, totalMeters: Double,
        scale: Float, centerX: Float, centerY: Float, label: String
    ) {
        val steps = 4
        paint.textSize = (18f * zoom).coerceIn(11f, 30f)
        for (i in 0..steps) {
            val frac = i.toFloat() / steps
            val px = from[0] + (to[0] - from[0]) * frac
            val py = from[1] + (to[1] - from[1]) * frac
            val pz = from[2] + (to[2] - from[2]) * frac
            val (sx, sy) = project(px, py, pz, scale, centerX, centerY)

            paint.color = Color.rgb(100, 220, 180)
            canvas.drawCircle(sx, sy, 3f, paint)
            paint.color = Color.WHITE
            val value = frac * totalMeters
            canvas.drawText("%.1fm".format(value), sx + 6f, sy - 6f, paint)
        }
        paint.color = Color.rgb(150, 220, 200)
        val (lx, ly) = project(from[0], from[1], from[2], scale, centerX, centerY)
        canvas.drawText(label, lx - 10f, ly + 25f, paint)
    }
}
