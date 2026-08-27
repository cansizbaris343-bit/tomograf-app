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
import kotlin.math.sin

class Tomography3DView(context: Context) : View(context) {

    private var grid: VoxelGrid? = null

    private var rotationY = 0.4f
    private var rotationX = 0.3f
    private var zoom = 1.0f

    private var depthSliceEnabled = false
    private var depthSliceFraction = 0.5f

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    private var selectedInfo: String? = null
    var onVoxelTapped: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.3f, 5.0f)
            invalidate()
            return true
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
        rotationY = 0.4f
        rotationX = 0.3f
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
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
                if (!isDragging) {
                    handleTap(event.x, event.y)
                }
            }
        }
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val g = grid ?: return
        val centerX = width / 2f
        val centerY = height / 2f
        val scale = minOf(width, height) / (g.nx.toFloat() * 2.2f) * zoom

        var closestIdx = -1
        var closestDist = Float.MAX_VALUE

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        for (iz in 0 until g.nz step 2) {
            if (depthSliceEnabled) {
                val depthFrac = iz.toFloat() / g.nz
                if (Math.abs(depthFrac - depthSliceFraction) > 0.08f) continue
            }
            for (iy in 0 until g.ny step 2) {
                for (ix in 0 until g.nx step 2) {
                    val idx = g.index(ix, iy, iz)
                    val v = g.velocities[idx]
                    val diff = (v - avg) / avg
                    if (Math.abs(diff) < 0.02) continue

                    val (sx, sy) = project(ix, iy, iz, g, centerX, centerY, scale)
                    val dist = (sx - screenX) * (sx - screenX) + (sy - screenY) * (sy - screenY)
                    if (dist < closestDist) {
                        closestDist = dist
                        closestIdx = idx
                    }
                }
            }
        }

        if (closestIdx >= 0 && closestDist < 900f) {
            val v = g.velocities[closestIdx]
            val diff = (v - avg) / avg * 100
            val izApprox = closestIdx / (g.nx * g.ny)
            val depthMeters = izApprox * g.voxelSizeMeters
            val tip = if (diff < 0) "dusuk yogunluk (bosluk ihtimali)" else "yuksek yogunluk (yogun/sert)"
            val msg = "Hiz: %.0f m/s (%.0f%% sapma) - %s\nDerinlik: %.2f m".format(v, diff, tip, depthMeters)
            selectedInfo = msg
            onVoxelTapped?.invoke(msg)
            invalidate()
        }
    }

    private fun project(ix: Int, iy: Int, iz: Int, g: VoxelGrid, centerX: Float, centerY: Float, scale: Float): Pair<Float, Float> {
        val px = (ix - g.nx / 2f) * scale
        val py = (iy - g.ny / 2f) * scale
        val pz = (iz - g.nz / 2f) * scale

        var rx = px * cos(rotationY) - pz * sin(rotationY)
        var rz = px * sin(rotationY) + pz * cos(rotationY)

        val ry = py * cos(rotationX) - rz * sin(rotationX)
        rz = py * sin(rotationX) + rz * cos(rotationX)

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
        val scale = minOf(width, height) / (g.nx.toFloat() * 2.2f) * zoom

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        for (iz in 0 until g.nz step 2) {
            if (depthSliceEnabled) {
                val depthFrac = iz.toFloat() / g.nz
                if (Math.abs(depthFrac - depthSliceFraction) > 0.08f) continue
            }
            for (iy in 0 until g.ny step 2) {
                for (ix in 0 until g.nx step 2) {
                    val idx = g.index(ix, iy, iz)
                    val v = g.velocities[idx]
                    val diff = (v - avg) / avg

                    if (Math.abs(diff) < 0.02) continue

                    val (screenX, screenY) = project(ix, iy, iz, g, centerX, centerY, scale)

                    paint.color = if (diff < 0) {
                        Color.rgb((80 + diff * -300).toInt().coerceIn(0, 255), 120, 255)
                    } else {
                        Color.rgb(255, (200 - diff * 300).toInt().coerceIn(0, 200), 60)
                    }
                    val radius = (6f + (Math.abs(diff) * 20).toFloat().coerceIn(2f, 18f)) * zoom
                    canvas.drawCircle(screenX, screenY, radius, paint)
                }
            }
        }

        drawDepthRuler(canvas, g)
        drawWidthRuler(canvas, g)

        paint.color = Color.LTGRAY
        paint.textSize = 24f
        canvas.drawText("Mavi = dusuk yogunluk | Turuncu/Kirmizi = yuksek yogunluk", 20f, height - 90f, paint)
        canvas.drawText("Iki parmakla zoom, tek parmakla dondur, dokunarak nokta bilgisi al", 20f, height - 55f, paint)

        if (depthSliceEnabled) {
            paint.color = Color.CYAN
            paint.textSize = 28f
            val depthMeters = depthSliceFraction * g.nz * g.voxelSizeMeters
            canvas.drawText("Kesit derinligi: %.2f m".format(depthMeters), 20f, height - 125f, paint)
        }

        selectedInfo?.let {
            paint.color = Color.YELLOW
            paint.textSize = 24f
            val lines = it.split("\n")
            for ((i, line) in lines.withIndex()) {
                canvas.drawText(line, 20f, 40f + i * 30f, paint)
            }
        }
    }

    private fun drawDepthRuler(canvas: Canvas, g: VoxelGrid) {
        val scale = minOf(width, height) / (g.nx.toFloat() * 2.2f) * zoom
        val totalDepthMeters = g.nz * g.voxelSizeMeters

        val rulerPixelLength = (g.nz * scale).coerceIn(80f, height * 3f)
        val rulerCenterY = height / 2f
        val rulerTop = (rulerCenterY - rulerPixelLength / 2f).coerceAtLeast(80f)
        val rulerBottom = (rulerCenterY + rulerPixelLength / 2f).coerceAtMost(height - 150f)
        val rulerX = width - 60f

        paint.color = Color.rgb(90, 90, 100)
        paint.strokeWidth = 3f
        canvas.drawLine(rulerX, rulerTop, rulerX, rulerBottom, paint)

        val steps = 7
        val baseTextSize = 20f
        paint.textSize = (baseTextSize * zoom).coerceIn(12f, 36f)
        for (i in 0..steps) {
            val frac = i.toFloat() / steps
            val y = rulerTop + frac * (rulerBottom - rulerTop)
            val depthValue = frac * totalDepthMeters

            paint.color = Color.rgb(90, 90, 100)
            canvas.drawLine(rulerX - 10f, y, rulerX, y, paint)
            paint.color = Color.WHITE
            canvas.drawText("%.1fm".format(depthValue), rulerX - 90f, y + 8f, paint)
        }

        paint.textSize = 20f
        paint.color = Color.LTGRAY
        canvas.drawText("Derinlik", rulerX - 100f, (rulerTop - 15f).coerceAtLeast(30f), paint)
    }

    private fun drawWidthRuler(canvas: Canvas, g: VoxelGrid) {
        val scale = minOf(width, height) / (g.nx.toFloat() * 2.2f) * zoom
        val totalWidthMeters = g.nx * g.voxelSizeMeters

        val rulerPixelLength = (g.nx * scale).coerceIn(80f, width * 3f)
        val rulerCenterX = width / 2f
        val rulerLeft = (rulerCenterX - rulerPixelLength / 2f).coerceAtLeast(20f)
        val rulerRight = (rulerCenterX + rulerPixelLength / 2f).coerceAtMost(width - 20f)
        val rulerY = 40f

        paint.color = Color.rgb(90, 90, 100)
        paint.strokeWidth = 3f
        canvas.drawLine(rulerLeft, rulerY, rulerRight, rulerY, paint)

        val steps = 6
        val baseTextSize = 20f
        paint.textSize = (baseTextSize * zoom).coerceIn(12f, 36f)
        for (i in 0..steps) {
            val frac = i.toFloat() / steps
            val x = rulerLeft + frac * (rulerRight - rulerLeft)
            val widthValue = frac * totalWidthMeters

            paint.color = Color.rgb(90, 90, 100)
            canvas.drawLine(x, rulerY, x, rulerY + 10f, paint)
            paint.color = Color.WHITE
            canvas.drawText("%.1fm".format(widthValue), x - 20f, rulerY + 35f, paint)
        }

        paint.textSize = 20f
        paint.color = Color.LTGRAY
        canvas.drawText("Genislik", rulerLeft, rulerY - 15f, paint)
    }
}
