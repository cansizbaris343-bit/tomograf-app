package com.tomograph.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tomograph.app.R
import com.tomograph.app.audio.AudioChirpTest
import com.tomograph.app.network.DaqUdpClient
import com.tomograph.app.network.SampleFrame
import com.tomograph.app.tomography.SirtInversion
import com.tomograph.app.tomography.StakeCoordinates
import com.tomograph.app.tomography.VoxelGrid
import com.tomograph.app.viewer.Tomography3DView
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var tomographyView: Tomography3DView
    private var daqClient: DaqUdpClient? = null
    private var depthSliceOn = false

    // Sahada olcumle otomatik tespit edilen nufuz derinligi. Bulunana kadar
    // varsayilan 1.5 metre kullanilir (gercekci, iddiali olmayan baslangic).
    private var detectedDepthMeters = 1.5

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
        val stakeCoordButton = findViewById<Button>(R.id.stakeCoordButton)
        val depthToggleButton = findViewById<Button>(R.id.depthToggleButton)
        val resetViewButton = findViewById<Button>(R.id.resetViewButton)
        val depthSlider = findViewById<SeekBar>(R.id.depthSlider)
        val container = findViewById<FrameLayout>(R.id.tomographyContainer)

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
        stakeCoordButton.setOnClickListener { showStakeCoordDialog(0) }

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
                    infoText.text = "Kesit derinligi: " + depthM + " m"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showStakeCoordDialog(stakeIndex: Int) {
        if (stakeIndex >= StakeCoordinates.positions.size) {
            infoText.text = "Tum kazik koordinatlari guncellendi:\n" + StakeCoordinates.summary()
            return
        }

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(48, 24, 48, 24)

        val current = StakeCoordinates.positions[stakeIndex]

        val xInput = EditText(this)
        xInput.hint = "X metre"
        xInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        xInput.setText(current[0].toString())

        val yInput = EditText(this)
        yInput.hint = "Y metre"
        yInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        yInput.setText(current[1].toString())

        val zInput = EditText(this)
        zInput.hint = "Z derinlik metre negatif"
        zInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        zInput.setText(current[2].toString())

        val infoLabel = TextView(this)
        infoLabel.text = "Kazik " + (stakeIndex + 1) + " - UCUN koordinatini girin"
        infoLabel.gravity = Gravity.CENTER
        infoLabel.setPadding(0, 0, 0, 24)

        layout.addView(infoLabel)
        layout.addView(xInput)
        layout.addView(yInput)
        layout.addView(zInput)

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Kazik koordinati")
        builder.setView(layout)
        builder.setPositiveButton("Kaydet ve Devam") { _, _ ->
            val x = xInput.text.toString().toDoubleOrNull() ?: current[0]
            val y = yInput.text.toString().toDoubleOrNull() ?: current[1]
            val z = zInput.text.toString().toDoubleOrNull() ?: current[2]
            StakeCoordinates.updatePosition(stakeIndex, x, y, z)
            showStakeCoordDialog(stakeIndex + 1)
        }
        builder.setNegativeButton("Iptal", null)
        builder.show()
    }

    private fun runMicTest() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), micPermissionRequestCode)
            statusText.text = "Mikrofon izni isteniyor..."
            return
        }
        startMicTest()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
        statusText.text = "4000-7777 Hz chirp sinyali hazirlaniyor..."
        AudioChirpTest.runTest { result ->
            runOnUiThread {
                statusText.text = result.message
            }
        }
    }

    private fun startAcquisition() {
        daqClient = DaqUdpClient(
            onFrame = { frame -> onFrameReceived(frame) },
            onError = { err -> runOnUiThread { statusText.text = "Baglanti hatasi: " + err.message } }
        )
        daqClient?.start()
        statusText.text = "Veri toplaniyor..."
    }

    private fun onFrameReceived(frame: SampleFrame) {
        // Gercek donanim baglandiginda, buraya biriken orneklerden
        // ArrivalTimePicker.detectPenetrationDepth() cagrilarak
        // detectedDepthMeters gercek zamanli guncellenecek.
    }

    private fun stopAcquisition() {
        daqClient?.stop()
        statusText.text = "Kayit durduruldu."
    }

    private fun runTestInversion() {
        statusText.text = "Test verisi uretiliyor ve SIRT hesaplaniyor..."

        val stakePositions = StakeCoordinates.positions

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

        // NOT: sideMeters artik sabit 7 degil, sahada tespit edilen (ya da
        // henuz olcum yapilmadiysa varsayilan 1.5m) gercek derinlik kullanilir.
        val voxelSize = 0.20
        val sideMeters = detectedDepthMeters.coerceAtLeast(0.3)
        val n = (sideMeters / voxelSize).toInt().coerceAtLeast(4)

        val grid = VoxelGrid(
            nx = n, ny = n, nz = n,
            originX = -sideMeters / 2.0,
            originY = -sideMeters / 2.0,
            originZ = -sideMeters,
            voxelSizeMeters = voxelSize
        )

        val c = n / 2
        val spread = (n / 8).coerceAtLeast(1)
        for (iz in (c - spread)..(c + spread)) {
            for (iy in (c - spread)..(c + spread)) {
                for (ix in (c - spread)..(c + spread)) {
                    if (grid.inBounds(ix, iy, iz)) {
                        grid.velocities[grid.index(ix, iy, iz)] = 900.0
                    }
                }
            }
        }

        val inversion = SirtInversion(grid, iterations = 60)
        val result = inversion.invert(rays)

        tomographyView.updateGrid(result)
        statusText.text = "Test tomografisi hazir, " + rays.size + " ray-path, simule veri."
        infoText.text = "Goruntulenen alan: " + sideMeters + "m x " + sideMeters + "m x " + sideMeters + "m " +
            "(tespit edilen/varsayilan derinlige gore)."
    }

    private fun distance3D(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
