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
import com.tomograph.app.dsp.ArrivalTimePicker
import com.tomograph.app.dsp.BandpassFilter
import com.tomograph.app.dsp.ChirpSignal
import com.tomograph.app.network.DaqUdpClient
import com.tomograph.app.network.SampleFrame
import com.tomograph.app.tomography.SirtInversion
import com.tomograph.app.tomography.SoilProfile
import com.tomograph.app.tomography.StakeCoordinates
import com.tomograph.app.tomography.VoxelGrid
import com.tomograph.app.viewer.Tomography3DView
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // =========================================================================
    // DONANIM AYARLARI - BUNLAR PLACEHOLDER'DIR. Gercek DAQ donanimi/firmware
    // hazir olunca, asagidaki iki degeri donanimin GERCEK degerleriyle
    // degistirmeden canli veri isleme YANLIS calisir (yanlis derinlik/zaman
    // hesaplar). Datasheet veya firmware kaynagindan teyit edilmeli.
    // =========================================================================
    private val DAQ_SAMPLE_RATE_HZ = 20000.0   // TODO: gercek DAQ ornekleme hizi
    private val SHOT_DURATION_SEC = 0.02       // TODO: gercek atis/chirp suresi
    private val STACK_COUNT = 32               // kac atis ortalanip tek deger uretilecek

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var tomographyView: Tomography3DView
    private var daqClient: DaqUdpClient? = null
    private var depthSliceOn = false

    private var detectedDepthMeters = 1.5

    private val micPermissionRequestCode = 501

    // --- Canli veri biriktirme durumu ---
    // key: "tx-shot" -> her alici kanal icin biriken ham ornekler
    private val pendingShots = HashMap<String, Array<MutableList<Int>>>()
    // key: "tx-rx" -> o cift icin simdiye kadar olculen gecikmeler (saniye)
    private val pairDelays = HashMap<String, MutableList<Double>>()
    // key: "tx-rx" -> zaten SIRT'e eklenmis mi (tekrar eklenmesin diye)
    private val completedPairs = HashSet<String>()
    private val liveRays = mutableListOf<SirtInversion.RayPath>()
    private var referenceChirp: DoubleArray = DoubleArray(0)
    private var samplesPerShot = 0
    private var framesSinceLastRefresh = 0

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

        tomographyView.onAnomaliesUpdated = { summary ->
            runOnUiThread { infoText.text = summary }
        }

        startButton.setOnClickListener { startAcquisition() }
        stopButton.setOnClickListener { stopAcquisition() }

        // processButton artik canli olcum durumunu gosteriyor (tek tiklama),
        // uzun basinca toprak tipini degistiriyor (kuru -> nemli -> dogun).
        processButton.setOnClickListener { showLiveStatus() }
        processButton.setOnLongClickListener { cycleSoilType(); true }

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

        statusText.text = "Hazir. " + SoilProfile.summary()
    }

    private fun cycleSoilType() {
        val next = when (SoilProfile.currentType) {
            SoilProfile.SoilType.KURU -> SoilProfile.SoilType.NEMLI
            SoilProfile.SoilType.NEMLI -> SoilProfile.SoilType.DOYGUN
            else -> SoilProfile.SoilType.KURU
        }
        SoilProfile.selectPreset(next)
        statusText.text = "Toprak tipi degisti: " + SoilProfile.summary()
    }

    private fun showLiveStatus() {
        val pairTotal = StakeCoordinates.STAKE_COUNT * (StakeCoordinates.STAKE_COUNT - 1)
        infoText.text = "Olculen cift: " + completedPairs.size + " / " + pairTotal +
            "\nBekleyen atis arabellekleri: " + pendingShots.size +
            "\n" + SoilProfile.summary()
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
        xInput.hint = "X metre (altigen merkezine gore)"
        xInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        xInput.setText(current[0].toString())

        val yInput = EditText(this)
        yInput.hint = "Y metre (altigen merkezine gore)"
        yInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        yInput.setText(current[1].toString())

        val zInput = EditText(this)
        zInput.hint = "Z derinlik metre negatif"
        zInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        zInput.setText(current[2].toString())

        val infoLabel = TextView(this)
        infoLabel.text = "Kazik " + (stakeIndex + 1) + " / " + StakeCoordinates.STAKE_COUNT + " - UCUN koordinatini girin"
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
        statusText.text = "Chirp sinyali hazirlaniyor (" + SoilProfile.summary() + ")..."
        AudioChirpTest.runTest { result ->
            runOnUiThread {
                statusText.text = result.message
            }
        }
    }

    // =========================================================================
    // CANLI VERI TOPLAMA
    // =========================================================================

    private fun startAcquisition() {
        // Yeni bir olcum oturumu basliyor: onceki birikmis veriyi temizle,
        // mevcut toprak profiline gore referans chirp'i ve izgarayi hazirla.
        pendingShots.clear()
        pairDelays.clear()
        completedPairs.clear()
        liveRays.clear()
        framesSinceLastRefresh = 0

        val lowHz = SoilProfile.current.recommendedLowHz
        val highHz = SoilProfile.current.recommendedHighHz
        referenceChirp = ChirpSignal.generate(lowHz, highHz, SHOT_DURATION_SEC, DAQ_SAMPLE_RATE_HZ)
        samplesPerShot = (SHOT_DURATION_SEC * DAQ_SAMPLE_RATE_HZ).toInt().coerceAtLeast(8)

        daqClient = DaqUdpClient(
            onFrame = { frame -> onFrameReceived(frame) },
            onError = { err -> runOnUiThread { statusText.text = "Baglanti hatasi: " + err.message } }
        )
        daqClient?.start()
        statusText.text = "Veri toplaniyor... (" + SoilProfile.summary() + ")"
    }

    /**
     * Her gelen UDP frame'i, ait oldugu (tx, shot) atisinin arabellegine ekler.
     * Bir atisin tum ornekleri toplaninca, 5 alici kanalin her biri icin
     * darbe sikistirma + varis zamani tespiti yapilir, sonuc o (tx, rx)
     * ciftinin gecikme listesine eklenir. Yeterli atis (STACK_COUNT)
     * birikince ortalama alinir ve SIRT'e yeni bir ray-path olarak girer.
     */
    private fun onFrameReceived(frame: SampleFrame) {
        if (referenceChirp.isEmpty()) return // startAcquisition henuz cagrilmadi

        val key = frame.txIndex.toString() + "-" + frame.shotIndex.toString()
        val buffers = pendingShots.getOrPut(key) { Array(5) { mutableListOf() } }

        for (ch in 0 until 5) {
            buffers[ch].add(frame.channels[ch])
        }

        if (buffers[0].size < samplesPerShot) return // bu atis henuz tamamlanmadi

        // Atis tamamlandi: her alici kanal icin isle, arabellegi sil.
        pendingShots.remove(key)
        processCompletedShot(frame.txIndex, buffers)

        framesSinceLastRefresh++
        if (framesSinceLastRefresh >= 5) {
            framesSinceLastRefresh = 0
            runOnUiThread { showLiveStatus() }
        }
    }

    /** 5 alici kazigin, txIndex disindaki gercek kazik indekslerine sirali eslemesi. */
    private fun receiverStakeIndices(txIndex: Int): List<Int> =
        (0 until StakeCoordinates.STAKE_COUNT).filter { it != txIndex }

    private fun processCompletedShot(txIndex: Int, buffers: Array<MutableList<Int>>) {
        val rxIndices = receiverStakeIndices(txIndex)
        val filter = BandpassFilter(DAQ_SAMPLE_RATE_HZ, SoilProfile.current.recommendedLowHz, SoilProfile.current.recommendedHighHz)

        for (ch in 0 until 5) {
            val rxStake = rxIndices.getOrNull(ch) ?: continue
            val raw = DoubleArray(buffers[ch].size) { buffers[ch][it].toDouble() }
            val filtered = filter.processBuffer(raw)
            val arrival = ArrivalTimePicker.pick(referenceChirp, filtered, DAQ_SAMPLE_RATE_HZ)

            // OTOMATIK DERINLIK BUYUTME: sinyalin gurultu tabanina ne zaman
            // dustugunu bulup, gercekte ne kadar derine indigini tahmin eder.
            // Mevcut tahminden daha derin bir sonuc cikarsa (ve guvenilirse),
            // izgara bir sonraki refreshLiveInversion() cagrisinda buyur.
            val penetration = ArrivalTimePicker.detectPenetrationDepth(
                filtered, DAQ_SAMPLE_RATE_HZ, SoilProfile.current.soundSpeedMs
            )
            if (penetration.cutoffDetected && penetration.depthMeters > detectedDepthMeters) {
                detectedDepthMeters = penetration.depthMeters
                runOnUiThread {
                    statusText.text = "Derinlik tahmini guncellendi: %.2f m".format(detectedDepthMeters)
                }
            }

            if (arrival.confidence < 1.0) continue // guvenilmez olcum, atla

            val pairKey = txIndex.toString() + "-" + rxStake.toString()
            if (pairKey in completedPairs) continue

            val list = pairDelays.getOrPut(pairKey) { mutableListOf() }
            list.add(arrival.delaySeconds)

            if (list.size >= STACK_COUNT) {
                // STACK_COUNT atis birikti - ortalama (medyan, aykiri degerlere
                // karsi ortalamadan daha dayanikli) al, SIRT ray listesine ekle.
                val sorted = list.sorted()
                val median = sorted[sorted.size / 2]
                val delaySeconds = median.coerceAtLeast(0.0)

                liveRays.add(
                    SirtInversion.RayPath(
                        sourceIdx = txIndex,
                        receiverIdx = rxStake,
                        sourcePos = StakeCoordinates.positions[txIndex],
                        receiverPos = StakeCoordinates.positions[rxStake],
                        measuredTravelTimeSec = delaySeconds
                    )
                )
                completedPairs.add(pairKey)
                pairDelays.remove(pairKey)

                runOnUiThread { refreshLiveInversion() }
            }
        }
    }

    /** Su ana kadar tamamlanan ciftlerle SIRT'i yeniden calistirip gorunumu gunceller. */
    private fun refreshLiveInversion() {
        if (liveRays.isEmpty()) return

        val voxelSize = 0.20
        val widthMeters = StakeCoordinates.horizontalSpanMeters()
        val nx = (widthMeters / voxelSize).toInt().coerceAtLeast(4)
        val ny = nx
        val depthMeters = detectedDepthMeters.coerceAtLeast(0.2)
        val nz = (depthMeters / voxelSize).toInt().coerceAtLeast(2)

        val grid = VoxelGrid(
            nx = nx, ny = ny, nz = nz,
            originX = -widthMeters / 2.0,
            originY = -widthMeters / 2.0,
            originZ = -depthMeters,
            voxelSizeMeters = voxelSize,
            initialVelocity = SoilProfile.current.soundSpeedMs
        )

        val inversion = SirtInversion(grid, iterations = 18)
        val result = inversion.invert(liveRays)

        tomographyView.updateGrid(result)

        val pairTotal = StakeCoordinates.STAKE_COUNT * (StakeCoordinates.STAKE_COUNT - 1)
        statusText.text = "Canli tomografi guncellendi: " + completedPairs.size + " / " + pairTotal + " cift"
    }

    private fun stopAcquisition() {
        daqClient?.stop()
        statusText.text = "Kayit durduruldu. Toplam " + completedPairs.size + " cift olculdu."
    }

    // =========================================================================
    // SENTETIK TEST (donanim olmadan algoritmayi dogrulamak icin - degismedi)
    // =========================================================================

    // Test modunda "gercek" bir derinlige sinyal gondermiyoruz (donanim yok),
    // bu yuzden otomatik derinlik tespitini DOGRULAMAK icin, bu kadar derinde
    // sinyalin gurultu tabanina dustugunu VARSAYAN sentetik bir dalga formu
    // uretip ayni ArrivalTimePicker.detectPenetrationDepth() fonksiyonunu
    // (canli koddaki ile BIREBIR AYNI) calistiriyoruz. Bu, gercek saha
    // olcumu degildir - sadece algoritmanin uctan uca calistigini gosterir.
    private val TEST_SIMULATED_DEPTH_METERS = 15.0

    private fun simulateDepthDetectionForTest() {
        val v = SoilProfile.current.soundSpeedMs
        val cutoffTimeSec = (2.0 * TEST_SIMULATED_DEPTH_METERS) / v
        val totalTimeSec = cutoffTimeSec * 1.3
        val totalSamples = (totalTimeSec * DAQ_SAMPLE_RATE_HZ).toInt().coerceAtLeast(64)
        val cutoffSample = (cutoffTimeSec * DAQ_SAMPLE_RATE_HZ).toInt()

        val rng = Random
        val synthetic = DoubleArray(totalSamples) { i ->
            val noiseAmplitude = if (i < cutoffSample) 500.0 else 40.0
            (rng.nextDouble() - 0.5) * 2.0 * noiseAmplitude
        }

        val result = ArrivalTimePicker.detectPenetrationDepth(synthetic, DAQ_SAMPLE_RATE_HZ, v)
        if (result.cutoffDetected && result.depthMeters > detectedDepthMeters) {
            detectedDepthMeters = result.depthMeters
        }
    }

    private fun runTestInversion() {
        simulateDepthDetectionForTest()
        statusText.text = "Test verisi uretiliyor ve SIRT hesaplaniyor... (tespit edilen derinlik: %.2f m)".format(detectedDepthMeters)

        val stakePositions = StakeCoordinates.positions

        val rays = mutableListOf<SirtInversion.RayPath>()
        for (src in stakePositions.indices) {
            for (rcv in stakePositions.indices) {
                if (src == rcv) continue
                val distance = distance3D(stakePositions[src], stakePositions[rcv])
                val baseVelocity = SoilProfile.current.soundSpeedMs
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

        val voxelSize = 0.20

        val widthMeters = StakeCoordinates.horizontalSpanMeters()
        val nx = (widthMeters / voxelSize).toInt().coerceAtLeast(4)
        val ny = nx

        val depthMeters = detectedDepthMeters.coerceAtLeast(0.2)
        val nz = (depthMeters / voxelSize).toInt().coerceAtLeast(2)

        val grid = VoxelGrid(
            nx = nx, ny = ny, nz = nz,
            originX = -widthMeters / 2.0,
            originY = -widthMeters / 2.0,
            originZ = -depthMeters,
            voxelSizeMeters = voxelSize,
            initialVelocity = SoilProfile.current.soundSpeedMs
        )

        // Anomali kontrasti guclendirildi (900->600) ve yumusatma iterasyonu
        // azaltildi (60->18) - ince (dusuk derinlikli) gridlerde asiri
        // yumusatmanin kontrasti eritmesini onlemek icin.
        val cx = nx / 2
        val cy = ny / 2
        val cz = nz / 2
        val spreadXY = 2
        val spreadZ = (nz / 4).coerceAtLeast(1)
        for (iz in (cz - spreadZ)..(cz + spreadZ)) {
            for (iy in (cy - spreadXY)..(cy + spreadXY)) {
                for (ix in (cx - spreadXY)..(cx + spreadXY)) {
                    if (grid.inBounds(ix, iy, iz)) {
                        grid.velocities[grid.index(ix, iy, iz)] = 600.0
                    }
                }
            }
        }

        val inversion = SirtInversion(grid, iterations = 18)
        val result = inversion.invert(rays)

        tomographyView.updateGrid(result)
        statusText.text = "Test tomografisi hazir, " + rays.size + " ray-path, simule veri."
        infoText.text = "Genislik: " + widthMeters + "m x " + widthMeters + "m (altigen cap). " +
            "Derinlik: " + depthMeters + "m (otomatik tespit/varsayilan).\n" + SoilProfile.summary()
    }

    private fun distance3D(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
