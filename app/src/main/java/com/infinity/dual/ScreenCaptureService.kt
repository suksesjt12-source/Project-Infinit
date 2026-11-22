package com.infinity.dual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
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
import kotlin.math.min

/**
 * User-started MediaProjection foreground service.
 * Android's consent dialog is required for each session; capture is stopped by the user
 * or by Android. Frames are downscaled, authenticated and AES-GCM encrypted before LAN send.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.infinity.dual.action.START_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_HOST = "stream_host"
        const val EXTRA_TOKEN = "stream_token"
        private const val NOTIFICATION_ID = 47823
        private const val CHANNEL_ID = "infinity_screen_capture"
        private const val MAX_FRAME_BYTES = 1_500_000
        private const val MAX_ENCRYPTED_FRAME_BYTES = MAX_FRAME_BYTES + 28
        private const val FRAME_INTERVAL_MS = 125L
        private const val CHALLENGE_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AAD = "PROJECT-INFINITY|SCREEN|V1"
    }

    @Volatile private var stopping = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var frameKey: SecretKeySpec? = null
    @Volatile private var lastFrameAt = 0L

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val outputLock = Any()
    private val secureRandom = SecureRandom()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = readProjectionIntent(intent)

        if (host.isBlank() || token.length != 48 || resultData == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopping = false
        startAsForeground()
        beginCapture(resultCode, resultData, host, token, startId)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionIntent(source: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            source.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen sharing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Menampilkan pemberitahuan saat layar dibagikan." }
            )
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PROJECT INFINITY")
            .setContentText("Layar sedang dibagikan ke perangkat pasangan.")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCapture(
        resultCode: Int,
        resultData: Intent,
        host: String,
        token: String,
        startId: Int
    ) {
        val thread = HandlerThread("InfinityScreenCapture").apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        Thread({
            try {
                val connected = Socket()
                connected.connect(
                    InetSocketAddress(host, ScreenStreamReceiver.PORT),
                    10_000
                )
                connected.tcpNoDelay = true
                connected.soTimeout = 15_000
                if (stopping) {
                    connected.close()
                    return@Thread
                }

                val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
                val networkOutput = DataOutputStream(connected.getOutputStream())

                val challengeLine = readProtocolLine(input)
                val challengeParts = challengeLine.split('|')
                if (challengeParts.size != 2 || challengeParts[0] != "SCREEN_CHALLENGE") {
                    throw IOException("Challenge screen stream tidak valid.")
                }
                val challengeBytes = runCatching {
                    Base64.getUrlDecoder().decode(challengeParts[1])
                }.getOrNull()
                if (challengeBytes == null || challengeBytes.size != CHALLENGE_BYTES) {
                    throw IOException("Challenge screen stream tidak valid.")
                }

                val clientProof = handshakeProof(token, "CLIENT", challengeParts[1])
                networkOutput.write("SCREEN_HELLO|$clientProof\n".toByteArray(Charsets.UTF_8))
                networkOutput.flush()

                val acknowledgement = readProtocolLine(input)
                val ackParts = acknowledgement.split('|')
                val expectedServerProof = handshakeProof(token, "SERVER", challengeParts[1])
                val authenticated = ackParts.size == 2 &&
                    ackParts[0] == "SCREEN_OK" &&
                    MessageDigest.isEqual(
                        ackParts[1].toByteArray(Charsets.UTF_8),
                        expectedServerProof.toByteArray(Charsets.UTF_8)
                    )
                if (!authenticated) {
                    throw IOException("Autentikasi penerima screen stream gagal.")
                }

                val key = deriveScreenKey(token)
                connected.soTimeout = 0
                if (stopping) {
                    connected.close()
                    return@Thread
                }
                socket = connected
                output = networkOutput
                frameKey = key

                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val activeProjection = manager.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("Izin screen capture tidak diberikan.")
                projection = activeProjection

                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf(startId)
                    }
                }
                projectionCallback = callback
                activeProjection.registerCallback(callback, handler)

                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

                val screenWidth = max(1, metrics.widthPixels)
                val screenHeight = max(1, metrics.heightPixels)
                val scale = min(1f, 960f / max(screenWidth, screenHeight).toFloat())
                val captureWidth = max(1, (screenWidth * scale).toInt())
                val captureHeight = max(1, (screenHeight * scale).toInt())

                val reader = ImageReader.newInstance(
                    captureWidth,
                    captureHeight,
                    PixelFormat.RGBA_8888,
                    2
                )
                imageReader = reader
                reader.setOnImageAvailableListener(
                    { source -> processLatestImage(source) },
                    handler
                )

                if (stopping) return@Thread
                virtualDisplay = activeProjection.createVirtualDisplay(
                    "PROJECT INFINITY Screen Share",
                    captureWidth,
                    captureHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler
                )
            } catch (error: Exception) {
                if (!stopping) {
                    android.util.Log.e("InfinityScreen", "Screen capture failed", error)
                }
                stopSelf(startId)
            }
        }, "InfinityScreenSetup").start()
    }

    private fun handshakeProof(token: String, role: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "SCREEN|$role|$challenge".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    private fun deriveScreenKey(token: String): SecretKeySpec {
        val material = "PROJECT-INFINITY|SCREEN-AES-256-GCM|$token"
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

    private fun processLatestImage(reader: ImageReader) {
        if (stopping) return
        var image: Image? = null
        var paddedBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            croppedBitmap = Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                image.width,
                image.height
            )

            val bytes = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 55, bytes)
            val frame = bytes.toByteArray()
            if (frame.isNotEmpty() && frame.size <= MAX_FRAME_BYTES) {
                val key = frameKey ?: return
                val encryptedFrame = encryptFrame(frame, key)
                if (encryptedFrame.size <= MAX_ENCRYPTED_FRAME_BYTES) {
                    synchronized(outputLock) {
                        val stream = output
                        if (!stopping && stream != null) {
                            stream.writeInt(encryptedFrame.size)
                            stream.write(encryptedFrame)
                            stream.flush()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (!stopping) {
                android.util.Log.w("InfinityScreen", "Frame send stopped", error)
                stopSelf()
            }
        } finally {
            image?.close()
            if (croppedBitmap != null && croppedBitmap !== paddedBitmap) {
                croppedBitmap.recycle()
            }
            paddedBitmap?.recycle()
        }
    }

    private fun readProtocolLine(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 256) {
            val value = input.read()
            if (value < 0) throw EOFException("Handshake stream terputus.")
            if (value == 10) return bytes.toString("UTF-8")
            if (value != 13) bytes.write(value)
        }
        throw IOException("Handshake stream terlalu panjang.")
    }

    override fun onDestroy() {
        stopping = true
        synchronized(outputLock) {
            runCatching { output?.close() }
            output = null
        }
        runCatching { socket?.close() }
        socket = null
        frameKey = null

        projectionCallback?.let { callback ->
            runCatching { projection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        stopForeground(true)
        super.onDestroy()
    }
}
