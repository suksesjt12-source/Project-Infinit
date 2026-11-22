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
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-peer local-network transport.
 *
 * Pairing uses a fresh challenge and HMAC proof so the QR token is never sent
 * over the network. Application frames are protected with AES-256-GCM.
 * The host keeps listening after a peer disconnects and the client retries
 * with bounded exponential backoff.
 */
class LanTransport {
    companion object {
        const val PORT = 47821
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
        private const val MAX_MESSAGE_LENGTH = 2_000
        private const val MAX_WIRE_MESSAGE_LENGTH = 8_192
        private const val MAX_RETRY_DELAY_MS = 8_000L
        private const val CHALLENGE_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val FRAME_PREFIX = "ENC|"
        private const val AAD = "PROJECT-INFINITY|LAN|V1"
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var activeKey: SecretKeySpec? = null
    @Volatile private var explicitlyClosed = false
    private val writeLock = Any()
    private val secureRandom = SecureRandom()

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
                    val output = BufferedWriter(
                        OutputStreamWriter(acceptedPeer.getOutputStream(), Charsets.UTF_8)
                    )

                    // A fresh random challenge prevents replaying a captured proof.
                    val challenge = newChallenge()
                    output.write("CHALLENGE|$challenge")
                    output.newLine()
                    output.flush()

                    val hello = reader.readLine()
                    if (!isValidClientProof(hello, expectedSession, expectedToken, challenge)) {
                        output.write("REJECT")
                        output.newLine()
                        output.flush()
                        withContext(Dispatchers.Main) {
                            onStatus("Pairing ditolak • bukti autentikasi tidak valid")
                        }
                        continue
                    }

                    val serverProof = handshakeProof(
                        expectedToken, "SERVER", expectedSession, challenge
                    )
                    output.write("WELCOME|$expectedSession|$serverProof")
                    output.newLine()
                    output.flush()

                    val key = deriveSessionKey(expectedSession, expectedToken)
                    acceptedPeer.soTimeout = 0
                    socket = acceptedPeer
                    writer = output
                    activeKey = key

                    val peerAddress = acceptedPeer.inetAddress.hostAddress ?: "perangkat lokal"
                    withContext(Dispatchers.Main) {
                        onStatus("Terhubung aman: $peerAddress • AES-GCM")
                    }

                    listen(acceptedPeer, reader, key, onMessage, onStatus)
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
                            activeKey = null
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
            activeKey = null
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

        // Reuse scanned pairing credentials until the user explicitly disconnects.
        while (!explicitlyClosed) {
            var peer: Socket? = null
            var retry = false
            var retryReason = "perangkat terputus"
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

                val challengeLine = reader.readLine()
                    ?: throw PairingRejectedException("Master menutup proses pairing.")
                val challengeParts = challengeLine.split('|')
                if (challengeParts.size != 2 || challengeParts[0] != "CHALLENGE") {
                    throw PairingRejectedException("Master tidak mengirim challenge pairing yang valid.")
                }
                val challengeBytes = runCatching {
                    Base64.getUrlDecoder().decode(challengeParts[1])
                }.getOrNull()
                if (challengeBytes == null || challengeBytes.size != CHALLENGE_BYTES) {
                    throw PairingRejectedException("Challenge pairing tidak valid.")
                }

                val clientProof = handshakeProof(
                    expectedToken, "CLIENT", expectedSession, challengeParts[1]
                )
                output.write("HELLO|$expectedSession|$clientProof")
                output.newLine()
                output.flush()

                val response = reader.readLine()
                val responseParts = response?.split('|') ?: emptyList()
                val expectedServerProof = handshakeProof(
                    expectedToken, "SERVER", expectedSession, challengeParts[1]
                )
                val validWelcome = responseParts.size == 3 &&
                    responseParts[0] == "WELCOME" &&
                    responseParts[1] == expectedSession &&
                    constantTimeEquals(responseParts[2], expectedServerProof)
                if (!validWelcome) {
                    throw PairingRejectedException(
                        "Autentikasi Master gagal. Pindai ulang QR dari sesi yang aktif."
                    )
                }

                val key = deriveSessionKey(expectedSession, expectedToken)
                connectedPeer.soTimeout = 0
                socket = connectedPeer
                writer = output
                activeKey = key
                attempt = 0
                withContext(Dispatchers.Main) {
                    onStatus("Terhubung aman ke $host • AES-GCM")
                }

                listen(connectedPeer, reader, key, onMessage, onStatus)
                retry = !explicitlyClosed
            } catch (error: PairingRejectedException) {
                if (!explicitlyClosed) {
                    withContext(Dispatchers.Main) {
                        onStatus(error.message ?: "Pairing ditolak")
                    }
                }
                break
            } catch (error: Exception) {
                retry = !explicitlyClosed
                retryReason = error.message ?: "kesalahan jaringan"
            } finally {
                val endedPeer = peer
                if (endedPeer != null) {
                    if (socket === endedPeer) {
                        socket = null
                        writer = null
                        activeKey = null
                    }
                    runCatching { endedPeer.close() }
                }
            }

            if (retry && !explicitlyClosed) {
                attempt += 1
                val waitMs = retryDelay(attempt)
                withContext(Dispatchers.Main) {
                    onStatus(
                        "Koneksi $retryReason • menyambung ulang ke $host dalam " +
                            "${waitMs / 1000} dtk (percobaan $attempt)"
                    )
                }
                delay(waitMs)
            }
        }
    }

    private fun newChallenge(): String {
        val bytes = ByteArray(CHALLENGE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun handshakeProof(
        token: String,
        role: String,
        session: String,
        challenge: String
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "$role|$session|$challenge".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    private fun isValidClientProof(
        line: String?,
        session: String,
        token: String,
        challenge: String
    ): Boolean {
        val parts = line?.split('|') ?: return false
        if (parts.size != 3 || parts[0] != "HELLO" || parts[1] != session) return false
        val expected = handshakeProof(token, "CLIENT", session, challenge)
        return constantTimeEquals(parts[2], expected)
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(
            first.toByteArray(Charsets.UTF_8),
            second.toByteArray(Charsets.UTF_8)
        )

    private fun deriveSessionKey(session: String, token: String): SecretKeySpec {
        val material = "PROJECT-INFINITY|AES-256-GCM|$session|$token"
            .toByteArray(Charsets.UTF_8)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(material)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptFrame(plainText: String, key: SecretKeySpec): String {
        val nonce = ByteArray(GCM_NONCE_BYTES)
        secureRandom.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packet = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, packet, 0, nonce.size)
        System.arraycopy(encrypted, 0, packet, nonce.size, encrypted.size)
        return FRAME_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packet)
    }

    private fun decryptFrame(line: String, key: SecretKeySpec): String {
        if (!line.startsWith(FRAME_PREFIX)) throw IOException("Frame tidak terenkripsi ditolak.")
        val packet = try {
            Base64.getUrlDecoder().decode(line.removePrefix(FRAME_PREFIX))
        } catch (_: IllegalArgumentException) {
            throw IOException("Format frame terenkripsi tidak valid.")
        }
        if (packet.size < GCM_NONCE_BYTES + 16) throw IOException("Frame terenkripsi terlalu pendek.")
        val nonce = packet.copyOfRange(0, GCM_NONCE_BYTES)
        val encrypted = packet.copyOfRange(GCM_NONCE_BYTES, packet.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val clearBytes = try {
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            throw IOException("Autentikasi AES-GCM gagal; frame ditolak.")
        }
        if (clearBytes.size > MAX_MESSAGE_LENGTH * 4) {
            throw IOException("Payload terdekripsi melebihi batas.")
        }
        return String(clearBytes, Charsets.UTF_8)
    }

    private fun retryDelay(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        return (1_000L * (1L shl exponent)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private suspend fun listen(
        peer: Socket,
        reader: BufferedReader,
        key: SecretKeySpec,
        onMessage: (String) -> Unit,
        onStatus: (String) -> Unit
    ) {
        try {
            while (!peer.isClosed && !explicitlyClosed) {
                val frame = reader.readLine() ?: break
                if (frame.length > MAX_WIRE_MESSAGE_LENGTH) {
                    throw IOException("Frame melebihi batas ukuran dan koneksi dihentikan.")
                }
                val clearText = decryptFrame(frame, key)
                if (clearText.length > MAX_MESSAGE_LENGTH) {
                    throw IOException("Pesan terdekripsi melebihi batas ukuran.")
                }
                withContext(Dispatchers.Main) { onMessage(clearText) }
            }
        } catch (error: Exception) {
            if (!explicitlyClosed) {
                withContext(Dispatchers.Main) {
                    onStatus("Koneksi dihentikan: ${error.message ?: "kesalahan keamanan/jaringan"}")
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
        val key = activeKey ?: return@withContext false

        synchronized(writeLock) {
            try {
                if (socket?.isClosed != false) return@synchronized false
                val frame = encryptFrame(clean, key)
                if (frame.length > MAX_WIRE_MESSAGE_LENGTH) return@synchronized false
                activeWriter.write(frame)
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
        activeKey = null
    }
}
