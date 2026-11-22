package com.infinity.dual

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * The host keeps listening after a peer disconnects. The client retries a
 * lost/unavailable connection with bounded exponential backoff. The QR token
 * authenticates pairing but does not encrypt the TCP connection.
 */
class LanTransport {
    companion object {
        const val PORT = 47821
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val MAX_MESSAGE_LENGTH = 2_000
        private const val MAX_RETRY_DELAY_MS = 8_000L
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var explicitlyClosed = false
    private val writeLock = Any()

    private class PairingRejectedException(message: String) : IOException(message)

    suspend fun host(
        expectedSession: String,
        expectedToken: String,
        onStatus: (String) -> Unit,
        onMessage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var listener: ServerSocket? = null
        explicitlyClosed = false
        try {
            val serverSocket = ServerSocket()
            listener = serverSocket
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(PORT))
            server = serverSocket

            withContext(Dispatchers.Main) {
                onStatus("Menunggu Client • port $PORT")
            }

            // Keep the same QR session alive so a dropped Client can reconnect.
            while (!explicitlyClosed && !serverSocket.isClosed) {
                var peer: Socket? = null
                try {
                    val acceptedPeer = serverSocket.accept()
                    peer = acceptedPeer
                    acceptedPeer.tcpNoDelay = true
                    acceptedPeer.soTimeout = HANDSHAKE_TIMEOUT_MS

                    val reader = BufferedReader(
                        InputStreamReader(acceptedPeer.getInputStream(), Charsets.UTF_8)
                    )
                    val hello = reader.readLine()
                    if (!isValidHandshake(hello, expectedSession, expectedToken)) {
                        runCatching {
                            val reject = BufferedWriter(
                                OutputStreamWriter(acceptedPeer.getOutputStream(), Charsets.UTF_8)
                            )
                            reject.write("REJECT")
                            reject.newLine()
                            reject.flush()
                        }
                        withContext(Dispatchers.Main) {
                            onStatus("Pairing ditolak • QR tidak cocok atau sudah diganti")
                        }
                        continue
                    }

                    val output = BufferedWriter(
                        OutputStreamWriter(acceptedPeer.getOutputStream(), Charsets.UTF_8)
                    )
                    output.write("WELCOME|$expectedSession")
                    output.newLine()
                    output.flush()

                    acceptedPeer.soTimeout = 0
                    socket = acceptedPeer
                    writer = output
                    val peerAddress = acceptedPeer.inetAddress.hostAddress ?: "perangkat lokal"
                    withContext(Dispatchers.Main) {
                        onStatus("Terhubung: $peerAddress")
                    }

                    listen(acceptedPeer, reader, onMessage, onStatus)
                } catch (error: Exception) {
                    if (!explicitlyClosed && !serverSocket.isClosed) {
                        withContext(Dispatchers.Main) {
                            onStatus("Client gagal: ${error.message ?: "kesalahan jaringan"}")
                        }
                    }
                } finally {
                    val endedPeer = peer
                    if (endedPeer != null) {
                        if (socket === endedPeer) {
                            socket = null
                            writer = null
                        }
                        runCatching { endedPeer.close() }
                    }
                }

                if (!explicitlyClosed && !serverSocket.isClosed) {
                    withContext(Dispatchers.Main) {
                        onStatus("Client terputus • menunggu koneksi ulang")
                    }
                }
            }
        } catch (error: Exception) {
            if (!explicitlyClosed) {
                withContext(Dispatchers.Main) {
                    onStatus("Host berhenti: ${error.message ?: "koneksi gagal"}")
                }
            }
        } finally {
            runCatching { listener?.close() }
            if (server === listener) server = null
            socket = null
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
        explicitlyClosed = false
        var attempt = 0

        // Reuse the scanned pairing credentials until the user explicitly disconnects.
        while (!explicitlyClosed) {
            var peer: Socket? = null
            var retry = false
            try {
                val connectedPeer = Socket()
                peer = connectedPeer
                connectedPeer.tcpNoDelay = true
                connectedPeer.connect(
                    InetSocketAddress(host.trim(), PORT),
                    HANDSHAKE_TIMEOUT_MS
                )
                connectedPeer.soTimeout = HANDSHAKE_TIMEOUT_MS

                val reader = BufferedReader(
                    InputStreamReader(connectedPeer.getInputStream(), Charsets.UTF_8)
                )
                val output = BufferedWriter(
                    OutputStreamWriter(connectedPeer.getOutputStream(), Charsets.UTF_8)
                )
                output.write("HELLO|$expectedSession|$expectedToken")
                output.newLine()
                output.flush()

                val response = reader.readLine()
                if (response == "REJECT" || response != "WELCOME|$expectedSession") {
                    throw PairingRejectedException("Pairing ditolak. Pindai QR Master yang masih aktif.")
                }

                connectedPeer.soTimeout = 0
                socket = connectedPeer
                writer = output
                attempt = 0
                withContext(Dispatchers.Main) {
                    onStatus("Terhubung ke $host")
                }

                listen(connectedPeer, reader, onMessage, onStatus)
                retry = !explicitlyClosed
            } catch (error: PairingRejectedException) {
                if (!explicitlyClosed) {
                    withContext(Dispatchers.Main) { onStatus(error.message ?: "Pairing ditolak") }
                }
                break
            } catch (error: Exception) {
                retry = !explicitlyClosed
                if (retry) {
                    attempt += 1
                    val waitMs = retryDelay(attempt)
                    withContext(Dispatchers.Main) {
                        onStatus(
                            "Koneksi gagal • mencoba ulang ke $host dalam ${waitMs / 1000} dtk " +
                                "(percobaan $attempt)"
                        )
                    }
                    delay(waitMs)
                }
            } finally {
                val endedPeer = peer
                if (endedPeer != null) {
                    if (socket === endedPeer) {
                        socket = null
                        writer = null
                    }
                    runCatching { endedPeer.close() }
                }
            }

            if (retry && !explicitlyClosed) {
                attempt += 1
                val waitMs = retryDelay(attempt)
                withContext(Dispatchers.Main) {
                    onStatus(
                        "Koneksi terputus • menyambung ulang ke $host dalam " +
                            "${waitMs / 1000} dtk (percobaan $attempt)"
                    )
                }
                delay(waitMs)
            }
        }
    }

    private fun retryDelay(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        return (1_000L * (1L shl exponent)).coerceAtMost(MAX_RETRY_DELAY_MS)
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
            while (!peer.isClosed && !explicitlyClosed) {
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
