package com.tomograph.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tomograph.app.R
import com.tomograph.app.audio.AudioChirpTest
import com.tomograph.app.network.DaqUdpClient
import com.tomograph.app.network.SampleFrame
import com.tomograph.app.tomography.SirtInversion
import com.tomograph.app.tomography.VoxelGrid
import com.tomograph.app.viewer.Tomography3DView
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var tomographyView: Tomography3DView
    private var daqClient: DaqUdpClient? = null
    private var depthSliceOn = false

    private val stakePositions = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.5, 0.3, -0.1),
        doubleArrayOf(0.9, -0.2, -0.15),
        doubleArrayOf(-0.4, -0.6, -0.05),
        doubleArrayOf(-0.7, 0.4, -0.2)
    )

    private val micPermissionRequestCode = 501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val processButton = findViewById<Button>(R.id.processButton)
        val testButton = findViewById<Button>(R.id.testButton)
        val micTestButton = findViewById<Button>(R.id.micTestButton)
        val depthToggleButton = findViewById<Button>(R.id.depthToggleButton)
        val resetViewButton = findViewById<Button>(R.id.resetViewButton)
        val depthSlider = findViewById<SeekBar>(R.id.depthSlider)
        val container = findViewById<android.widget.FrameLayout>(R.id.tomographyContainer)

        tomographyView = Tomography3DView(this)
        container.addView(tomographyView)

        tomographyView.onVoxelTapped = { info ->
            runOnUiThread { infoText.text = info }
        }

        startButton.setOnClickListener { startAcquisition() }
        stopButton.setOnClickListener { stopAcquisition() }
        processButton.setOnClickListener { statusText.text = "Gercek veri icin donanim baglantisi gerekiyor." }
        testButton.setOnClickListener { runTestInversion() }
        micTestButton.setOnClickListener { runMicTest() }

        depthToggleButton.setOnClickListener {
            depthSliceOn = !depthSliceOn
            tomographyView.setDepthSliceEnabled(depthSliceOn)
            depthToggleButton.text = if (depthSliceOn) "Kesit Modu: Acik" else "Kesit Modu: Kapali"
        }

        resetViewButton.setOnClickListener {
            tomographyView.resetView()
        }

        depthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tomographyView.setDepthSliceFraction(progress / 100f)
                if (depthSliceOn) {
                    val depthM = tomographyView.getDepthSliceMeters()
                    infoText.text = "Kesit derinligi: %.2f m".format(depthM)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun runMicTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), micPermissionRequestCode
            )
            statusText.text = "Mikrofon izni isteniyor..."
            return
        }
        startMicTest()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micPermissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMicTest()
            } else {
                statusText.text = "Mikrofon izni verilmedi. Test yapilamiyor."
            }
        }
    }

    private fun startMicTest() {
        statusText.text = "4000-7777 Hz chirp sinyali hazirlaniyor... Telefonu sessiz bir ortamda tutun."
        AudioChirpTest.runTest { result ->
            runOnUiThread {
                statusText.text = result.message
            }
        }
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

        for (iz in 8..12) for (iy in 8..12) for (ix in 8..12) {
            grid.velocities[grid.index(ix, iy, iz)] = 900.0
        }

        val inversion = SirtInversion(grid, iterations = 60)
        val result = inversion.invert(rays)

        tomographyView.updateGrid(result)
        statusText.text = "Test tomografisi hazir (${rays.size} ray-path, simule veri)."
        infoText.text = "Noktalara dokunarak detay gorebilir, iki parmakla yakinlastirabilirsiniz."
    }

    private fun distance3D(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
