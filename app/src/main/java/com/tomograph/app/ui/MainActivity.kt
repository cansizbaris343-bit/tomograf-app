package com.tomograph.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tomograph.app.R
import com.tomograph.app.network.DaqUdpClient
import com.tomograph.app.network.SampleFrame
import com.tomograph.app.tomography.SirtInversion
import com.tomograph.app.tomography.VoxelGrid
import com.tomograph.app.viewer.Tomography3DView
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var tomographyView: Tomography3DView
    private var daqClient: DaqUdpClient? = null

    private val stakePositions = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.5, 0.3, -0.1),
        doubleArrayOf(0.9, -0.2, -0.15),
        doubleArrayOf(-0.4, -0.6, -0.05),
        doubleArrayOf(-0.7, 0.4, -0.2)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val processButton = findViewById<Button>(R.id.processButton)
        val testButton = findViewById<Button>(R.id.testButton)
        val container = findViewById<android.widget.FrameLayout>(R.id.tomographyContainer)

        tomographyView = Tomography3DView(this)
        container.addView(tomographyView)

        startButton.setOnClickListener { startAcquisition() }
        stopButton.setOnClickListener { stopAcquisition() }
        processButton.setOnClickListener { statusText.text = "Gercek veri icin donanim baglantisi gerekiyor." }
        testButton.setOnClickListener { runTestInversion() }
    }

    private fun startAcquisition() {
        daqClient = DaqUdpClient(
            onFrame = { frame -> onFrameReceived(frame) },
            onError = { err -> runOnUiThread { statusText.text = "Baglanti hatasi: ${err.message}" } }
        )
        daqClient?.start()
        statusText.text = "Veri toplaniyor..."
    }

    private fun onFrameReceived(frame: SampleFrame) {
        // Ham veri burada islenecek
    }

    private fun stopAcquisition() {
        daqClient?.stop()
        statusText.text = "Kayit durduruldu."
    }

    private fun runTestInversion() {
        statusText.text = "Test verisi uretiliyor ve SIRT hesaplaniyor..."

        val rays = mutableListOf<SirtInversion.RayPath>()
        for (src in stakePositions.indices) {
            for (rcv in stakePositions.indices) {
                if (src == rcv) continue
                val distance = distance3D(stakePositions[src], stakePositions[rcv])
                val baseVelocity = 1500.0
                val simulatedTime = distance / baseVelocity * (0.9 + Random.nextDouble() * 0.2)
                rays.add(
                    SirtInversion.RayPath(
                        sourceIdx = src,
                        receiverIdx = rcv,
                        sourcePos = stakePositions[src],
                        receiverPos = stakePositions[rcv],
                        measuredTravelTimeSec = simulatedTime
                    )
                )
            }
        }

        val grid = VoxelGrid(
            nx = 20, ny = 20, nz = 20,
            originX = -1.2, originY = -1.2, originZ = -1.5,
            voxelSizeMeters = 0.12
        )

        // Test amacli, ortada yapay bir "bosluk" (dusuk hiz) bolgesi olustur
        for (iz in 8..12) for (iy in 8..12) for (ix in 8..12) {
            grid.velocities[grid.index(ix, iy, iz)] = 900.0
        }

        val inversion = SirtInversion(grid, iterations = 60)
        val result = inversion.invert(rays)

        tomographyView.updateGrid(result)
        statusText.text = "Test tomografisi hazir (${rays.size} ray-path, simule veri). Gorseli dondurmek icin ekrana surukleyin."
    }

    private fun distance3D(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
