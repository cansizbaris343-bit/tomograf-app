package com.tomograph.app.network

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROTOKOL (DAQ donanimi/firmware ile ES ZAMANLI olmali):
 *   - timestampMicros : Long  (8 bayt)
 *   - sampleIndex     : Int->Long (4 bayt, isaretsiz)
 *   - txIndex         : Int  (4 bayt) - O AN HANGI KAZIGIN VERICI OLARAK
 *                        ATESLENDIGINI belirtir (0-5 arasi, StakeCoordinates
 *                        sirasina gore). Bu alan olmadan telefon, gelen
 *                        5 kanalin HANGI tx-rx ciftine ait oldugunu bilemez.
 *   - shotIndex       : Int  (4 bayt) - kacinci atis oldugunu belirtir,
 *                        ayni (tx,shotIndex)'e ait ornekler birlestirilip
 *                        tek bir "atis" olarak islenir. Ardisik atislar
 *                        farkli shotIndex tasimalidir (coklu atis
 *                        ortalamasi/stacking icin gerekli).
 *   - channels[5]     : Int x5 (20 bayt) - 5 alici kazigin o anki ham
 *                        ornek degeri (ADC ciktisi).
 *
 * Toplam frame boyutu: 8+4+4+4+20 = 40 bayt.
 *
 * ONEMLI: txIndex ve shotIndex alanlari bu projede YENI eklendi - DAQ
 * donaniminin/firmware'inin bu iki alani da paket icine koyacak sekilde
 * GUNCELLENMESI gerekiyor. Guncellenmezse eski 36 baytlik paketler bu
 * parser ile hatali okunur.
 */
data class SampleFrame(
    val timestampMicros: Long,
    val sampleIndex: Long,
    val txIndex: Int,
    val shotIndex: Int,
    val channels: IntArray
)

class DaqUdpClient(
    private val listenPort: Int = 5005,
    private val onFrame: (SampleFrame) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        job = scope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(listenPort))
                }
                val buf = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    parsePacket(packet.data, packet.length)
                }
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    private fun parsePacket(data: ByteArray, length: Int) {
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val frameSize = 8 + 4 + 4 + 4 + 5 * 4
        while (bb.remaining() >= frameSize) {
            val ts = bb.long
            val idx = bb.int.toLong() and 0xFFFFFFFFL
            val tx = bb.int
            val shot = bb.int
            val channels = IntArray(5) { bb.int }
            onFrame(SampleFrame(ts, idx, tx, shot, channels))
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
    }
}
