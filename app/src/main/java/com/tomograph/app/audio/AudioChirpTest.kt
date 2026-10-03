package com.tomograph.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.tomograph.app.dsp.ArrivalTimePicker
import com.tomograph.app.dsp.BandpassFilter
import com.tomograph.app.tomography.SoilProfile
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Telefonun kendi hoparlor ve mikrofonunu kullanarak, calisma bandindaki
 * chirp sinyalinin gercekten uretilip yakalanabildigini dogrulayan
 * bagimsiz test.
 *
 * BANT: Sabit kodlanmis degil - SoilProfile.current.recommendedLowHz/HighHz
 * degerlerinden okunur. Toprak tipi (kuru/nemli/doygun) degisince bu test de
 * otomatik olarak dogru bandi kullanir.
 *
 * Bu, gercek tomografi olcumu DEGILDIR (hava yoluyla hoparlor->mikrofon
 * dogrudan gider, zeminden gecmez). Sadece donanim + DSP algoritmasinin
 * (BandpassFilter, ArrivalTimePicker) gercekten calistigini kanitlar.
 */
object AudioChirpTest {

    private const val SAMPLE_RATE = 44100
    private const val CHIRP_DURATION_SEC = 0.3

    data class TestResult(
        val success: Boolean,
        val message: String,
        val delayMillis: Double = 0.0,
        val confidence: Double = 0.0
    )

    fun runTest(onResult: (TestResult) -> Unit) {
        thread {
            try {
                val f0 = SoilProfile.current.recommendedLowHz
                val f1 = SoilProfile.current.recommendedHighHz

                val chirp = generateChirp(f0, f1)
                val recordSamples = SAMPLE_RATE * 2 // 2 saniyelik kayit penceresi

                val recordBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(recordSamples * 2)

                @Suppress("MissingPermission")
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize
                )

                val recordedShorts = ShortArray(recordSamples)

                recorder.startRecording()

                // Kayit basladiktan kisa bir sure sonra chirp'i cal (senkron icin)
                Thread.sleep(150)
                playChirp(chirp)

                var totalRead = 0
                while (totalRead < recordSamples) {
                    val n = recorder.read(recordedShorts, totalRead, recordSamples - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }

                recorder.stop()
                recorder.release()

                if (totalRead < recordSamples / 2) {
                    onResult(TestResult(false, "Yeterli ses kaydi alinamadi. Mikrofon izni verildi mi?"))
                    return@thread
                }

                val recordedDouble = DoubleArray(totalRead) { recordedShorts[it].toDouble() }

                val filter = BandpassFilter(SAMPLE_RATE.toDouble(), f0, f1)
                val filtered = filter.processBuffer(recordedDouble)

                val arrival = ArrivalTimePicker.pick(chirp, filtered, SAMPLE_RATE.toDouble())

                val delayMs = arrival.delaySeconds * 1000.0

                val success = arrival.confidence > 1.5 && delayMs in -50.0..1500.0

                val bandInfo = "%.0f-%.0f Hz".format(f0, f1)
                val msg = if (success) {
                    "Basarili (%s bandi): Chirp yakalandi. Gecikme: %.1f ms, Guven: %.1f"
                        .format(bandInfo, delayMs, arrival.confidence)
                } else {
                    "Sinyal net tespit edilemedi (bant: %s, guven: %.2f). Ortam gurultusunu azaltip, sesi acip tekrar deneyin."
                        .format(bandInfo, arrival.confidence)
                }

                onResult(TestResult(success, msg, delayMs, arrival.confidence))

            } catch (se: SecurityException) {
                onResult(TestResult(false, "Mikrofon izni verilmedi."))
            } catch (t: Throwable) {
                onResult(TestResult(false, "Hata: ${t.message}"))
            }
        }
    }

    private fun generateChirp(f0: Double, f1: Double): DoubleArray {
        val n = (CHIRP_DURATION_SEC * SAMPLE_RATE).toInt()
        val k = (f1 - f0) / CHIRP_DURATION_SEC
        return DoubleArray(n) { i ->
            val t = i / SAMPLE_RATE.toDouble()
            sin(2 * PI * (f0 * t + 0.5 * k * t * t))
        }
    }

    private fun playChirp(chirp: DoubleArray) {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize.coerceAtLeast(chirp.size * 2),
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        val shortData = ShortArray(chirp.size) { (chirp[it] * Short.MAX_VALUE * 0.9).toInt().toShort() }
        track.write(shortData, 0, shortData.size)
        track.play()
        Thread.sleep((CHIRP_DURATION_SEC * 1000).toLong() + 100)
        track.release()
    }
}
