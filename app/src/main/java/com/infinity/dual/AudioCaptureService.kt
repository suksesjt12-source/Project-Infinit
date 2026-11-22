package com.infinity.dual

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
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
 * Explicitly started foreground microphone service.
 * It sends only microphone PCM to the paired Master after the user grants
 * RECORD_AUDIO and starts the stream from the visible app UI.
 */
class AudioCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.infinity.dual.action.START_AUDIO_STREAM"
        const val ACTION_STOP = "com.infinity.dual.action.STOP_AUDIO_STREAM"
        const val EXTRA_HOST = "audio_stream_host"
        const val EXTRA_TOKEN = "audio_stream_token"
        private const val NOTIFICATION_ID = 47825
        private const val CHANNEL_ID = "infinity_audio_capture"
        private const val SAMPLE_RATE = AudioStreamReceiver.SAMPLE_RATE
        private const val FRAME_BYTES = 3_200 // 100 ms of mono 16-bit PCM at 16 kHz
        private const val MAX_ENCRYPTED_FRAME_BYTES = 6_428
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AAD = "PROJECT-INFINITY|AUDIO|V1"
        private const val CHALLENGE_BYTES = 32
    }

    @Volatile private var stopping = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private val outputLock = Any()
    private val secureRandom = SecureRandom()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (host.isBlank() || !token.matches(Regex("[A-Fa-f0-9]{48}")) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopping = false
        startAsForeground()
        Thread({ connectAndCapture(host, token) }, "InfinityAudioCapture").start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Microphone streaming",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Menampilkan pemberitahuan saat mikrofon sedang dibagikan."
                }
            )
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PROJECT INFINITY")
            .setContentText("Mikrofon sedang dibagikan ke perangkat pasangan.")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun connectAndCapture(host: String, token: String) {
        try {
            val connected = Socket()
            connected.connect(InetSocketAddress(host, AudioStreamReceiver.PORT), 10_000)
            connected.tcpNoDelay = true
            connected.soTimeout = 15_000
            if (stopping) {
                connected.close()
                return
            }

            val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
            val networkOutput = DataOutputStream(connected.getOutputStream())
            val challengeLine = readProtocolLine(input)
            val challengeParts = challengeLine.split('|')
            if (challengeParts.size != 2 || challengeParts[0] != "AUD_CHALLENGE") {
                throw IOException("Challenge audio stream tidak valid.")
            }
            val challengeBytes = runCatching {
                Base64.getUrlDecoder().decode(challengeParts[1])
            }.getOrNull()
            if (challengeBytes == null || challengeBytes.size != CHALLENGE_BYTES) {
                throw IOException("Challenge audio stream tidak valid.")
            }

            val clientProof = handshakeProof(token, "CLIENT", challengeParts[1])
            networkOutput.write("AUD_HELLO|$clientProof\n".toByteArray(Charsets.UTF_8))
            networkOutput.flush()

            val acknowledgement = readProtocolLine(input)
            val ackParts = acknowledgement.split('|')
            val expectedServerProof = handshakeProof(token, "SERVER", challengeParts[1])
            val authenticated = ackParts.size == 2 &&
                ackParts[0] == "AUD_OK" &&
                MessageDigest.isEqual(
                    ackParts[1].toByteArray(Charsets.UTF_8),
                    expectedServerProof.toByteArray(Charsets.UTF_8)
                )
            if (!authenticated) throw IOException("Autentikasi penerima audio gagal.")

            if (stopping) {
                connected.close()
                return
            }
            connected.soTimeout = 0
            socket = connected
            val stream = DataOutputStream(connected.getOutputStream())
            output = stream
            captureMicrophone(stream, deriveKey(token))
        } catch (error: Exception) {
            if (!stopping) {
                android.util.Log.e("InfinityAudio", "Audio stream failed", error)
            }
            stopSelf()
        }
    }

    private fun captureMicrophone(stream: DataOutputStream, key: SecretKeySpec) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Izin mikrofon belum diberikan.")
        }

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            throw IOException("Perangkat tidak mendukung input PCM mono 16 kHz.")
        }

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minimum, FRAME_BYTES * 2))
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("AudioRecord gagal diinisialisasi.")
        }

        audioRecord = recorder
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Mikrofon tidak mulai merekam.")
            }

            val buffer = ByteArray(FRAME_BYTES)
            while (!stopping) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) throw IOException("AudioRecord gagal membaca data: $count")
                if (count == 0) continue

                val pcm = if (count == buffer.size) buffer else buffer.copyOf(count)
                val encrypted = encryptFrame(pcm, key)
                if (encrypted.size !in 1..MAX_ENCRYPTED_FRAME_BYTES) {
                    throw IOException("Paket audio terlalu besar.")
                }

                synchronized(outputLock) {
                    if (stopping) break
                    stream.writeInt(encrypted.size)
                    stream.write(encrypted)
                    stream.flush()
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            if (audioRecord === recorder) audioRecord = null
        }
    }

    private fun handshakeProof(token: String, role: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "AUDIO|$role|$challenge".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    private fun deriveKey(token: String): SecretKeySpec {
        val material = "PROJECT-INFINITY|AUDIO-AES-256-GCM|$token"
            .toByteArray(Charsets.UTF_8)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material), "AES")
    }

    private fun encryptFrame(frame: ByteArray, key: SecretKeySpec): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(frame)
        val packet = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, packet, 0, nonce.size)
        System.arraycopy(encrypted, 0, packet, nonce.size, encrypted.size)
        return packet
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

    override fun onDestroy() {
        stopping = true
        synchronized(outputLock) {
            runCatching { output?.flush() }
            output = null
        }
        runCatching { audioRecord?.stop() }
        audioRecord = null
        runCatching { socket?.close() }
        socket = null
        stopForeground(true)
        super.onDestroy()
    }
}
