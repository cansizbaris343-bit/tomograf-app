package com.tomograph.app.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tomograph.app.tomography.VoxelGrid
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
    private var isDragging = false
    private var isScaling = false

    private var selectedInfo: String? = null
    var onVoxelTapped: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.25f, 6.0f)
            invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    init {
        setBackgroundColor(Color.rgb(15, 15, 20))
    }

    fun updateGrid(newGrid: VoxelGrid) {
        grid = newGrid
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

    // Buyuk gridlerde performans icin adim (step) degerini otomatik ayarlar
    private fun adaptiveStep(g: VoxelGrid): Int {
        val totalVoxels = g.nx * g.ny * g.nz
        return when {
            totalVoxels > 40000 -> 4
            totalVoxels > 15000 -> 3
            else -> 2
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isDragging = true // ikinci parmak degince surukleme/tap iptal
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !isScaling) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) isDragging = true
                    rotationY += dx * 0.01f
                    rotationX = (rotationX + dy * 0.01f).coerceIn(-1.4f, 1.4f)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && !isScaling && event.pointerCount == 1) {
                    handleTap(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun baseScale(g: VoxelGrid): Float {
        val maxDim = max(g.nx, max(g.ny, g.nz)).toFloat()
        return minOf(width, height) / (maxDim * 2.4f) * zoom
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val g = grid ?: return
        val centerX = width / 2f
        val centerY = height / 2f
        val scale = baseScale(g)
        val step = adaptiveStep(g)

        var closestIdx = -1
        var closestDist = Float.MAX_VALUE

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        for (iz in 0 until g.nz step step) {
            if (depthSliceEnabled) {
                val depthFrac = iz.toFloat() / g.nz
                if (Math.abs(depthFrac - depthSliceFraction) > 0.08f) continue
            }
            for (iy in 0 until g.ny step step) {
                for (ix in 0 until g.nx step step) {
                    val idx = g.index(ix, iy, iz)
                    val v = g.velocities[idx]
                    val diff = (v - avg) / avg
                    if (Math.abs(diff) < 0.02) continue

                    val (sx, sy) = project(ix - g.nx / 2f, iy - g.ny / 2f, iz - g.nz / 2f, scale, centerX, centerY)
                    val dist = (sx - screenX) * (sx - screenX) + (sy - screenY) * (sy - screenY)
                    if (dist < closestDist) {
                        closestDist = dist
                        closestIdx = idx
                    }
                }
            }
        }

        if (closestIdx >= 0 && closestDist < 1000f) {
            val v = g.velocities[closestIdx]
            val diff = (v - avg) / avg * 100
            val izApprox = closestIdx / (g.nx * g.ny)
            val depthMeters = izApprox * g.voxelSizeMeters
            val tip = if (diff < 0) "dusuk yogunluk (bosluk ihtimali)" else "yuksek yogunluk (yogun/sert)"
            selectedInfo = "Hiz: %.0f m/s (%.0f%% sapma) - %s\nDerinlik: %.2f m".format(v, diff, tip, depthMeters)
            onVoxelTapped?.invoke(selectedInfo!!)
            invalidate()
        }
    }

    // 3B nokta (grid merkezine gore, metre*voxel biriminde degil, izgara birimi cinsinden)
    // once döndür sonra ekrana izdusur
    private fun project(px: Float, py: Float, pz: Float, scale: Float, centerX: Float, centerY: Float): Pair<Float, Float> {
        val sx = px * scale
        val sy = py * scale
        val sz = pz * scale

        var rx = sx * cos(rotationY) - sz * sin(rotationY)
        var rz = sx * sin(rotationY) + sz * cos(rotationY)

        val ry = sy * cos(rotationX) - rz * sin(rotationX)
        rz = sy * sin(rotationX) + rz * cos(rotationX)

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
        val step = adaptiveStep(g)

        drawWireframeCube(canvas, g, scale, centerX, centerY)

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        for (iz in 0 until g.nz step step) {
            if (depthSliceEnabled) {
                val depthFrac = iz.toFloat() / g.nz
                if (Math.abs(depthFrac - depthSliceFraction) > 0.08f) continue
            }
            for (iy in 0 until g.ny step step) {
                for (ix in 0 until g.nx step step) {
                    val idx = g.index(ix, iy, iz)
                    val v = g.velocities[idx]
                    val diff = (v - avg) / avg
                    if (Math.abs(diff) < 0.02) continue

                    val (screenX, screenY) = project(ix - g.nx / 2f, iy - g.ny / 2f, iz - g.nz / 2f, scale, centerX, centerY)

                    paint.color = if (diff < 0) {
                        Color.rgb((80 + diff * -300).toInt().coerceIn(0, 255), 120, 255)
                    } else {
                        Color.rgb(255, (200 - diff * 300).toInt().coerceIn(0, 200), 60)
                    }
                    val radius = (5f + (Math.abs(diff) * 18).toFloat().coerceIn(2f, 16f))
                    canvas.drawCircle(screenX, screenY, radius, paint)
                }
            }
        }

        paint.color = Color.LTGRAY
        paint.textSize = 24f
        canvas.drawText("Mavi = dusuk yogunluk | Turuncu/Kirmizi = yuksek yogunluk", 20f, height - 55f, paint)

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
     * Tarama hacminin sinirlarini bir tel-kafes kup olarak cizer.
     * Kupun kenarlari, noktalarla AYNI dondurme/zoom donusumunu kullanir -
     * boylece kup ve uzerindeki olcu etiketleri gorüntuyle birlikte doner
     * ve buyur/kuculur (ekrana sabit degildir).
     */
    private fun drawWireframeCube(canvas: Canvas, g: VoxelGrid, scale: Float, centerX: Float, centerY: Float) {
        val hx = g.nx / 2f
        val hy = g.ny / 2f
        val hz = g.nz / 2f

        // Kupun 8 kosesi (izgara birimi cinsinden, merkeze gore)
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

        // Genislik (X) olcusu - alt on kenar boyunca (kose 0-1)
        drawEdgeRuler(canvas, corners[0], corners[1], g.nx * g.voxelSizeMeters, scale, centerX, centerY, "Genislik X")
        // Derinlik (Y, yatay ikinci eksen) - alt on kenar (kose 0-3)
        drawEdgeRuler(canvas, corners[0], corners[3], g.ny * g.voxelSizeMeters, scale, centerX, centerY, "Genislik Y")
        // Derinlik (Z, dikey) - sol on dikey kenar (kose 0-4)
        drawEdgeRuler(canvas, corners[0], corners[4], g.nz * g.voxelSizeMeters, scale, centerX, centerY, "Derinlik")
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
