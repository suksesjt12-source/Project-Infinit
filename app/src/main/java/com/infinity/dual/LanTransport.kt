package com.infinity.dual

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress

class LanTransport {
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null

    suspend fun host(onStatus: (String) -> Unit, onMessage: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val listener = ServerSocket(47821)
                server = listener
                withContext(Dispatchers.Main) { onStatus("Menunggu perangkat di port 47821") }
                val peer = listener.accept()
                socket = peer
                writer = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
                withContext(Dispatchers.Main) { onStatus("Terhubung: ${peer.inetAddress.hostAddress}") }
                listen(peer, onMessage, onStatus)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Koneksi terputus: ${e.message ?: "error"}") }
            } finally { close() }
        }
    }

    suspend fun connect(host: String, onStatus: (String) -> Unit, onMessage: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val peer = Socket()
                peer.connect(InetSocketAddress(host.trim(), 47821), 7000)
                socket = peer
                writer = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
                withContext(Dispatchers.Main) { onStatus("Terhubung ke $host") }
                listen(peer, onMessage, onStatus)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("Gagal terhubung: ${e.message ?: "periksa IP dan Wi-Fi"}") }
            } finally { close() }
        }
    }

    private suspend fun listen(peer: Socket, onMessage: (String) -> Unit, onStatus: (String) -> Unit) {
        try {
            val reader = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.UTF_8))
            while (!peer.isClosed) {
                val line = reader.readLine() ?: break
                withContext(Dispatchers.Main) { onMessage(line) }
            }
        } catch (_: Exception) {
        } finally { withContext(Dispatchers.Main) { onStatus("Perangkat terputus") } }
    }

    suspend fun send(message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val active = writer ?: return@withContext false
            synchronized(this@LanTransport) {
                active.write(message.replace("\n", " "))
                active.newLine()
                active.flush()
            }
            true
        } catch (_: Exception) { false }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        socket = null
        server = null
        writer = null
    }
}