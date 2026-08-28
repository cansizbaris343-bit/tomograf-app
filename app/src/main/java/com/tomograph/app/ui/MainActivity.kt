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
                    infoText.text = "Kesit derinligi: %.2f m".format(depthM)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }private fun showStakeCoordDialog(stakeIndex: Int) {
        if (stakeIndex >= StakeCoordinates.positions.size) {
            infoText.text = "Tum kazik koordinatlari guncellendi:\n" + StakeCoordinates.summary()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val current = StakeCoordinates.positions[stakeIndex]

        val xInput = EditText(this).apply {
            hint = "X metre"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current[0].toString())
        }
        val yInput = EditText(this).apply {
            hint = "Y metre"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current[1].toString())
        }
        val zInput = EditText(this).apply {
            hint = "Z derinlik metre negatif"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current[2].toString())
        }

        val infoLabel = TextView(this).apply {
            text = "Kazik " + (stakeIndex + 1) + " - UCUN koordinatini girin"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        layout.addView(infoLabel)
        layout.addView(xInput)
        layout.addView(yInput)
        layout.addView(zInput)

        AlertDialog.Builder(this)
            .setTitle("Kazik koordinati")
            .setView(layout)
            .setPositiveButton("Kaydet ve Devam") { _, _ ->
                val x = xInput.text.toString().toDoubleOrNull() ?: current[0]
                val y = yInput.text.toString().toDoubleOrNull() ?: current[1]
                val z = zInput.text.toString().toDoubleOrNull() ?: current[2]
                private fun startAcquisition() {
        daqClient = DaqUdpClient(
            onFrame = { frame -> onFrameReceived(frame) },
            onError = { err -> runOnUiThread { statusText.text = "Baglanti hatasi: " + err.message } }
        )
        daqClient?.start()
        statusText.text = "Veri toplaniyor..."
    }

    private fun onFrameReceived(frame: SampleFrame) {
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

        val xs = stakePositions.map { it[0] }
        val ys = stakePositions.map { it[1] }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1.0) + 1.0
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1.0) + 1.0

        val voxelSize = 0.20
        val nx = (spanX / voxelSize).toInt().coerceAtLeast(10)
        val ny = (spanY / voxelSize).toInt().coerceAtLeast(10)

        val depthMeters = 7.0
        val nz = (depthMeters / voxelSize).toInt()

        val grid = VoxelGrid(
            nx = nx, ny = ny, nz = nz,
            originX = -(nx * voxelSize) / 2.0,
            originY = -(ny * voxelSize) / 2.0,
            originZ = -depthMeters,
            voxelSizeMeters = voxelSize
        )

        val cx = nx / 2
        val cy = ny / 2
        val cz = nz / 2
        for (iz in (cz - 2)..(cz + 2)) {
            for (iy in (cy - 2)..(cy + 2)) {
                for (ix in (cx - 2)..(cx + 2)) {
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
        infoText.text = "Goruntulenen alan hesaplandi. Derinlik 7 metre."
    }

    private fun distance3D(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
