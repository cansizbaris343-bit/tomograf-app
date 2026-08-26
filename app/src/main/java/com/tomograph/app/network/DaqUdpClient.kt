package com.tomograph.app.network

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SampleFrame(
    val timestampMicros: Long,
    val sampleIndex: Long,
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
        val frameSize = 12 + 5 * 4
        while (bb.remaining() >= frameSize) {
            val ts = bb.long
            val idx = bb.int.toLong() and 0xFFFFFFFFL
            val channels = IntArray(5) { bb.int }
            onFrame(SampleFrame(ts, idx, channels))
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
    }
}
