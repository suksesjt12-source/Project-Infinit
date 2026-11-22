package com.infinity.dual

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * One-peer local-network transport.
 *
 * A short-lived token from the host QR is verified before the connection is
 * considered paired. This is a pairing check, not transport encryption.
 */
class LanTransport {
    companion object {
        const val PORT = 47821
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val MAX_MESSAGE_LENGTH = 2_000
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var explicitlyClosed = false
    private val writeLock = Any()

    suspend fun host(
        expectedSession: String,
        expectedToken: String,
        onStatus: (String) -> Unit,
        onMessage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var listener: ServerSocket? = null
        var peer: Socket? = null
        explicitlyClosed = false
        try {
            listener = ServerSocket()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(PORT))
            server = listener
            withContext(Dispatchers.Main) {
                onStatus("Menunggu client • port $PORT")
            }

            peer = listener.accept()
            peer.tcpNoDelay = true
            peer.soTimeout = HANDSHAKE_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.UTF_8))
            val hello = reader.readLine()
            if (!isValidHandshake(hello, expectedSession, expectedToken)) {
                runCatching {
                    val reject = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
                    reject.write("REJECT")
                    reject.newLine()
                    reject.flush()
                }
                throw IOException("Pairing ditolak. QR tidak cocok atau sudah diganti.")
            }

            val output = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
            output.write("WELCOME|$expectedSession")
            output.newLine()
            output.flush()

            peer.soTimeout = 0
            socket = peer
            writer = output
            withContext(Dispatchers.Main) {
                onStatus("Terhubung: ${peer.inetAddress.hostAddress}")
            }
            listen(peer, reader, onMessage, onStatus)
        } catch (error: Exception) {
            if (!explicitlyClosed) {
                withContext(Dispatchers.Main) {
                    onStatus("Host berhenti: ${error.message ?: "koneksi gagal"}")
                }
            }
        } finally {
            runCatching { peer?.close() }
            runCatching { listener?.close() }
            if (socket === peer) socket = null
            if (server === listener) server = null
            writer = null
        }
    }

    suspend fun connect(
        host: String,
        expectedSession: String,
        expectedToken: String,
        onStatus: (String) -> Unit,
        onMessage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var peer: Socket? = null
        explicitlyClosed = false
        try {
            peer = Socket()
            peer.tcpNoDelay = true
            peer.connect(InetSocketAddress(host.trim(), PORT), HANDSHAKE_TIMEOUT_MS)
            peer.soTimeout = HANDSHAKE_TIMEOUT_MS

            val reader = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.UTF_8))
            val output = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
            output.write("HELLO|$expectedSession|$expectedToken")
            output.newLine()
            output.flush()

            val response = reader.readLine()
            if (response != "WELCOME|$expectedSession") {
                throw IOException("Pairing ditolak oleh perangkat Master.")
            }

            peer.soTimeout = 0
            socket = peer
            writer = output
            withContext(Dispatchers.Main) {
                onStatus("Terhubung ke $host")
            }
            listen(peer, reader, onMessage, onStatus)
        } catch (error: Exception) {
            if (!explicitlyClosed) {
                withContext(Dispatchers.Main) {
                    onStatus("Gagal terhubung: ${error.message ?: "periksa Wi-Fi dan QR"}")
                }
            }
        } finally {
            runCatching { peer?.close() }
            if (socket === peer) socket = null
            writer = null
        }
    }

    private fun isValidHandshake(line: String?, session: String, token: String): Boolean {
        val parts = line?.split('|') ?: return false
        if (parts.size != 3 || parts[0] != "HELLO" || parts[1] != session) return false
        return MessageDigest.isEqual(
            parts[2].toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
    }

    private suspend fun listen(
        peer: Socket,
        reader: BufferedReader,
        onMessage: (String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        try {
            while (!peer.isClosed) {
                val line = reader.readLine() ?: break
                if (line.length > MAX_MESSAGE_LENGTH) {
                    withContext(Dispatchers.Main) {
                        onStatus("Pesan terlalu panjang dan diabaikan")
                    }
                    continue
                }
                withContext(Dispatchers.Main) { onMessage(line) }
            }
        } catch (error: Exception) {
            if (!explicitlyClosed) {
                withContext(Dispatchers.Main) {
                    onStatus("Koneksi terputus: ${error.message ?: "kesalahan jaringan"}")
                }
            }
        } finally {
            if (!explicitlyClosed) {
                withContext(Dispatchers.Main) { onStatus("Perangkat terputus") }
            }
        }
    }

    suspend fun send(message: String): Boolean = withContext(Dispatchers.IO) {
        val clean = message.trim().replace('\n', ' ').replace('\r', ' ')
        if (clean.isEmpty() || clean.length > MAX_MESSAGE_LENGTH) return@withContext false
        val activeWriter = writer ?: return@withContext false

        synchronized(writeLock) {
            try {
                if (socket?.isClosed != false) return@synchronized false
                activeWriter.write(clean)
                activeWriter.newLine()
                activeWriter.flush()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun close() {
        explicitlyClosed = true
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
        writer = null
    }
}
