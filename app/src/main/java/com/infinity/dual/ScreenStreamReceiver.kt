package com.infinity.dual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
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
 * Receives a one-way JPEG screen stream from a paired client.
 * The QR token authenticates a fresh challenge-response handshake; every
 * JPEG frame is then authenticated and encrypted with AES-256-GCM.
 */
class ScreenStreamReceiver {
    companion object {
        const val PORT = 47822
        private const val MAX_FRAME_BYTES = 1_500_000
        private const val MAX_ENCRYPTED_FRAME_BYTES = MAX_FRAME_BYTES + 28
        private const val CHALLENGE_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AAD = "PROJECT-INFINITY|SCREEN|V1"
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var peer: Socket? = null
    @Volatile private var closed = false
    private val secureRandom = SecureRandom()

    suspend fun listen(
        token: String,
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var listener: ServerSocket? = null
        var client: Socket? = null
        closed = false
        try {
            val created = ServerSocket()
            listener = created
            created.reuseAddress = true
            created.bind(InetSocketAddress(PORT))
            server = created
            withContext(Dispatchers.Main) {
                onStatus("Menunggu screen share aman pada port $PORT")
            }

            val connected = created.accept()
            client = connected
            peer = connected
            connected.tcpNoDelay = true
            connected.soTimeout = 15_000

            val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
            val output = DataOutputStream(connected.getOutputStream())

            val challenge = newChallenge()
            output.write("SCREEN_CHALLENGE|$challenge\n".toByteArray(Charsets.UTF_8))
            output.flush()

            val hello = readProtocolLine(input)
            if (!isValidClientProof(hello, token, challenge)) {
                output.write("REJECT\n".toByteArray(Charsets.UTF_8))
                output.flush()
                throw IOException("Autentikasi screen stream ditolak.")
            }

            val serverProof = handshakeProof(token, "SERVER", challenge)
            output.write("SCREEN_OK|$serverProof\n".toByteArray(Charsets.UTF_8))
            output.flush()

            val key = deriveScreenKey(token)
            connected.soTimeout = 0
            withContext(Dispatchers.Main) {
                onStatus("Screen stream terenkripsi AES-GCM aktif")
            }

            while (!closed && !connected.isClosed) {
                val frameSize = input.readInt()
                if (frameSize !in 1..MAX_ENCRYPTED_FRAME_BYTES) {
                    throw IOException("Ukuran frame terenkripsi tidak valid.")
                }
                val encrypted = ByteArray(frameSize)
                input.readFully(encrypted)
                val bytes = decryptFrame(encrypted, key)
                if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) {
                    throw IOException("Ukuran gambar setelah dekripsi tidak valid.")
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: continue
                withContext(Dispatchers.Main) {
                    if (!closed) {
                        onFrame(bitmap)
                        onStatus("Screen streaming aman aktif • AES-GCM")
                    } else {
                        bitmap.recycle()
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed) {
                withContext(Dispatchers.Main) {
                    onStatus("Screen stream berhenti: " + (error.message ?: "koneksi terputus"))
                }
            }
        } finally {
            runCatching { client?.close() }
            runCatching { listener?.close() }
            if (peer === client) peer = null
            if (server === listener) server = null
        }
    }

    private fun newChallenge(): String {
        val bytes = ByteArray(CHALLENGE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun handshakeProof(token: String, role: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "SCREEN|$role|$challenge".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    private fun isValidClientProof(line: String, token: String, challenge: String): Boolean {
        val parts = line.split('|')
        if (parts.size != 2 || parts[0] != "SCREEN_HELLO") return false
        val expected = handshakeProof(token, "CLIENT", challenge)
        return MessageDigest.isEqual(
            parts[1].toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun deriveScreenKey(token: String): SecretKeySpec {
        val material = "PROJECT-INFINITY|SCREEN-AES-256-GCM|$token"
            .toByteArray(Charsets.UTF_8)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material), "AES")
    }

    private fun decryptFrame(packet: ByteArray, key: SecretKeySpec): ByteArray {
        if (packet.size < NONCE_BYTES + 16) throw IOException("Frame terenkripsi terlalu pendek.")
        val nonce = packet.copyOfRange(0, NONCE_BYTES)
        val encrypted = packet.copyOfRange(NONCE_BYTES, packet.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return try {
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            throw IOException("Autentikasi frame AES-GCM gagal.")
        }
    }

    private fun readProtocolLine(input: DataInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 256) {
            val value = input.read()
            if (value < 0) throw EOFException("Handshake stream terputus.")
            if (value == 10) return bytes.toString("UTF-8")
            if (value != 13) bytes.write(value)
        }
        throw IOException("Handshake stream terlalu panjang.")
    }

    fun close() {
        closed = true
        runCatching { peer?.close() }
        runCatching { server?.close() }
        peer = null
        server = null
    }
}
