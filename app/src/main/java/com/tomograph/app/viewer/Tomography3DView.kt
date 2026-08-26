package com.tomograph.app.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.MotionEvent
import com.tomograph.app.tomography.VoxelGrid
import kotlin.math.cos
import kotlin.math.sin

class Tomography3DView(context: Context) : View(context) {

    private var grid: VoxelGrid? = null
    private var rotationY = 0.4f
    private var lastX = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.rgb(15, 15, 20))
    }

    fun updateGrid(newGrid: VoxelGrid) {
        grid = newGrid
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE) {
            val dx = event.x - lastX
            rotationY += dx * 0.01f
            invalidate()
        }
        lastX = event.x
        return true
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
        val scale = minOf(width, height) / (g.nx.toFloat() * 2.2f)

        var sum = 0.0
        for (v in g.velocities) sum += v
        val avg = sum / g.velocities.size

        for (iz in 0 until g.nz step 2) {
            for (iy in 0 until g.ny step 2) {
                for (ix in 0 until g.nx step 2) {
                    val idx = g.index(ix, iy, iz)
                    val v = g.velocities[idx]
                    val diff = (v - avg) / avg

                    if (Math.abs(diff) < 0.02) continue

                    val px = (ix - g.nx / 2f) * scale
                    val py = (iy - g.ny / 2f) * scale
                    val pz = (iz - g.nz / 2f) * scale

                    val rx = px * cos(rotationY) - pz * sin(rotationY)
                    val rz = px * sin(rotationY) + pz * cos(rotationY)

                    val screenX = centerX + rx
                    val screenY = centerY + py - rz * 0.3f

                    paint.color = if (diff < 0) {
                        Color.rgb((80 + diff * -300).toInt().coerceIn(0, 255), 120, 255)
                    } else {
                        Color.rgb(255, (200 - diff * 300).toInt().coerceIn(0, 200), 60)
                    }
                    val radius = 6f + (Math.abs(diff) * 20).toFloat().coerceIn(2f, 18f)
                    canvas.drawCircle(screenX, screenY, radius, paint)
                }
            }
        }

        paint.color = Color.LTGRAY
        paint.textSize = 28f
        canvas.drawText("Mavi = dusuk yogunluk (bosluk ihtimali)", 30f, height - 80f, paint)
        canvas.drawText("Turuncu/Kirmizi = yuksek yogunluk", 30f, height - 40f, paint)
    }
}
