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

    // Kazik konumlari (metre, birbirine gore x,y,z). Sahada kaziklari cakarken
    // gercek olcum yapip bu degerleri guncelleyin - o zaman goruntulenen alan
    // gercek kazik araligini yansitir.
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

        // Kazik pozisyonlarinin kapladigi gercek yatay alani hesapla (x ve y min/max)
        val xs = stakePositions.map { it[0] }
        val ys = stakePositions.map { it[1] }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1.0) + 1.0 // kenar payi
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1.0) + 1.0

        // Genislik (nx,ny): kazik alanini kapsayacak sekilde, hucre boyutu 0.20m
        val voxelSize = 0.20
        val nx = ((spanX / voxelSize).toInt()).coerceAtLeast(10)
        val ny = ((spanY / voxelSize).toInt()).coerceAtLeast(10)

        // Derinlik (nz): 7 metre sabit, hucre boyutu 0.20m -> 35 hucre
        val depthMeters = 7.0
        val nz = (depthMeters / voxelSize).toInt()

        val grid = VoxelGrid(
            nx = nx, ny = ny, nz = nz,
            originX = -(nx * voxelSize) / 2.0,
            originY = -(ny * voxelSize) / 2.0,
            originZ = -depthMeters,
            voxelSizeMeters = voxelSize
        )

        // Test amacli, ortada yapay bir "bosluk" (dusuk hiz) bolgesi olustur
        val cx = nx / 2; val cy = ny / 2; val cz = nz / 2
        for (iz in (cz - 2)..(cz + 2)) for (iy in (cy - 2)..(cy + 2)) for (ix in (cx - 2)..(cx + 2)) {
            if (grid.inBounds(ix, iy, iz)) {
                grid.velocities[grid.index(ix, iy, iz)] = 900.0
            }
        }

        val inversion = SirtInversion(grid, iterations = 60)
        val result = inversion.invert(rays)

        tomographyView.updateGrid(result)
        statusText.text = "Test tomografisi hazir (${rays.size} ray-path, simule veri)."
        infoText.text = "Goruntulenen alan: %.1fm x %.1fm, derinlik 7.0m. Noktalara dokunarak detay gorebilirsiniz."
            .format(nx * voxelSize, ny * voxelSize)
    }

    private fun distance3D(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
