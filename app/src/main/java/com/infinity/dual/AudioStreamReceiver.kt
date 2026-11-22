package com.infinity.dual

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
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
import kotlin.math.max

/**
 * Receives opt-in microphone audio from the paired Client and plays it locally.
 * Each connection has a fresh HMAC challenge and AES-GCM authenticated frames.
 */
class AudioStreamReceiver {
    companion object {
        const val PORT = 47825
        const val SAMPLE_RATE = 16_000
        private const val MAX_AUDIO_FRAME_BYTES = 6_400
        private const val MAX_ENCRYPTED_FRAME_BYTES = MAX_AUDIO_FRAME_BYTES + 28
        private const val CHALLENGE_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AAD = "PROJECT-INFINITY|AUDIO|V1"
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var peer: Socket? = null
    @Volatile private var closed = false

    suspend fun listen(
        token: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var listener: ServerSocket? = null
        closed = false
        try {
            val created = ServerSocket()
            listener = created
            created.reuseAddress = true
            created.bind(InetSocketAddress(PORT))
            server = created
            withContext(Dispatchers.Main) {
                onStatus("Menunggu audio stream pada port $PORT")
            }

            // Remain ready for another explicit Client start/stop cycle.
            while (!closed && !created.isClosed) {
                var connected: Socket? = null
                var track: AudioTrack? = null
                try {
                    val accepted = created.accept()
                    connected = accepted
                    peer = accepted
                    accepted.tcpNoDelay = true
                    accepted.soTimeout = 15_000

                    val input = DataInputStream(BufferedInputStream(accepted.getInputStream()))
                    val output = accepted.getOutputStream()
                    val challenge = newChallenge()
                    output.write("AUD_CHALLENGE|$challenge\n".toByteArray(Charsets.UTF_8))
                    output.flush()

                    val hello = readProtocolLine(input)
                    if (!validClientProof(hello, token, challenge)) {
                        output.write("REJECT\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                        throw IOException("Autentikasi audio stream ditolak.")
                    }

                    val serverProof = handshakeProof(token, "SERVER", challenge)
                    output.write("AUD_OK|$serverProof\n".toByteArray(Charsets.UTF_8))
                    output.flush()

                    val key = deriveKey(token)
                    accepted.soTimeout = 0
                    track = createAudioTrack()
                    withContext(Dispatchers.Main) {
                        onStatus("Audio stream aktif • PCM 16 kHz • AES-GCM")
                    }

                    while (!closed && !accepted.isClosed) {
                        val frameSize = input.readInt()
                        if (frameSize !in 1..MAX_ENCRYPTED_FRAME_BYTES) {
                            throw IOException("Ukuran paket audio tidak valid.")
                        }
                        val packet = ByteArray(frameSize)
                        input.readFully(packet)
                        val pcm = decryptFrame(packet, key)
                        if (pcm.isEmpty() || pcm.size > MAX_AUDIO_FRAME_BYTES || pcm.size % 2 != 0) {
                            throw IOException("Data PCM audio tidak valid.")
                        }
                        val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) throw IOException("AudioTrack gagal memutar audio: $written")
                    }
                } catch (error: Exception) {
                    if (!closed && !created.isClosed) {
                        withContext(Dispatchers.Main) {
                            onStatus("Audio stream berhenti: ${error.message ?: "koneksi terputus"}")
                        }
                    }
                } finally {
                    track?.let { active ->
                        runCatching { active.pause() }
                        runCatching { active.flush() }
                        runCatching { active.stop() }
                        runCatching { active.release() }
                    }
                    val ended = connected
                    if (ended != null) {
                        runCatching { ended.close() }
                        if (peer === ended) peer = null
                    }
                }

                if (!closed && !created.isClosed) {
                    withContext(Dispatchers.Main) {
                        onStatus("Audio Client terputus • menunggu Mulai kembali")
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed) {
                withContext(Dispatchers.Main) {
                    onStatus("Penerima audio berhenti: ${error.message ?: "kesalahan jaringan"}")
                }
            }
        } finally {
            runCatching { listener?.close() }
            if (server === listener) server = null
            peer = null
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) throw IOException("AudioTrack tidak mendukung format PCM 16 kHz.")
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minimum, 6_400))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IOException("AudioTrack gagal diinisialisasi.")
        }
        track.play()
        return track
    }

    private fun newChallenge(): String {
        val bytes = ByteArray(CHALLENGE_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun handshakeProof(token: String, role: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "AUDIO|$role|$challenge".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    private fun validClientProof(line: String, token: String, challenge: String): Boolean {
        val parts = line.split('|')
        if (parts.size != 2 || parts[0] != "AUD_HELLO") return false
        val expected = handshakeProof(token, "CLIENT", challenge)
        return MessageDigest.isEqual(
            parts[1].toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun deriveKey(token: String): SecretKeySpec {
        val material = "PROJECT-INFINITY|AUDIO-AES-256-GCM|$token"
            .toByteArray(Charsets.UTF_8)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material), "AES")
    }

    private fun decryptFrame(packet: ByteArray, key: SecretKeySpec): ByteArray {
        if (packet.size < NONCE_BYTES + 16) throw IOException("Paket audio terenkripsi terlalu pendek.")
        val nonce = packet.copyOfRange(0, NONCE_BYTES)
        val encrypted = packet.copyOfRange(NONCE_BYTES, packet.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return try {
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            throw IOException("Verifikasi paket audio AES-GCM gagal.")
        }
    }

    private fun readProtocolLine(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 256) {
            val value = input.read()
            if (value < 0) throw EOFException("Handshake audio stream terputus.")
            if (value == 10) return bytes.toString("UTF-8")
            if (value != 13) bytes.write(value)
        }
        throw IOException("Handshake audio stream terlalu panjang.")
    }

    fun close() {
        closed = true
        runCatching { peer?.close() }
        runCatching { server?.close() }
        peer = null
        server = null
    }
}
