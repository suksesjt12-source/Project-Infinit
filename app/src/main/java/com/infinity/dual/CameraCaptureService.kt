package com.infinity.dual

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Size
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
 * User-started camera foreground service. The app requires a runtime camera
 * permission and shows an ongoing notification while the camera is active.
 * Camera frames are sent only to the paired LAN host using AES-GCM.
 */
class CameraCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.infinity.dual.action.START_CAMERA_STREAM"
        const val ACTION_STOP = "com.infinity.dual.action.STOP_CAMERA_STREAM"
        const val EXTRA_HOST = "camera_stream_host"
        const val EXTRA_TOKEN = "camera_stream_token"
        private const val NOTIFICATION_ID = 47824
        private const val CHANNEL_ID = "infinity_camera_capture"
        private const val MAX_FRAME_BYTES = 600_000
        private const val MAX_ENCRYPTED_FRAME_BYTES = MAX_FRAME_BYTES + 28
        private const val FRAME_INTERVAL_MS = 200L
        private const val CHALLENGE_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AAD = "PROJECT-INFINITY|CAMERA|V1"
    }

    @Volatile private var stopping = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var frameKey: SecretKeySpec? = null
    @Volatile private var lastFrameAt = 0L

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
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
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopping = false
        startAsForeground()
        Thread({ connectAndStartCamera(host, token) }, "InfinityCameraNetwork").start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Camera streaming",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Menampilkan pemberitahuan saat kamera dibagikan."
                }
            )
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PROJECT INFINITY")
            .setContentText("Kamera sedang dibagikan ke perangkat pasangan.")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun connectAndStartCamera(host: String, token: String) {
        try {
            val connected = Socket()
            connected.connect(InetSocketAddress(host, CameraStreamReceiver.PORT), 10_000)
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
            if (challengeParts.size != 2 || challengeParts[0] != "CAM_CHALLENGE") {
                throw IOException("Challenge camera stream tidak valid.")
            }
            val challengeBytes = runCatching {
                Base64.getUrlDecoder().decode(challengeParts[1])
            }.getOrNull()
            if (challengeBytes == null || challengeBytes.size != CHALLENGE_BYTES) {
                throw IOException("Challenge camera stream tidak valid.")
            }

            val clientProof = handshakeProof(token, "CLIENT", challengeParts[1])
            networkOutput.write("CAM_HELLO|$clientProof\n".toByteArray(Charsets.UTF_8))
            networkOutput.flush()

            val acknowledgement = readProtocolLine(input)
            val ackParts = acknowledgement.split('|')
            val expectedServerProof = handshakeProof(token, "SERVER", challengeParts[1])
            val authenticated = ackParts.size == 2 &&
                ackParts[0] == "CAM_OK" &&
                MessageDigest.isEqual(
                    ackParts[1].toByteArray(Charsets.UTF_8),
                    expectedServerProof.toByteArray(Charsets.UTF_8)
                )
            if (!authenticated) throw IOException("Autentikasi penerima kamera gagal.")

            if (stopping) {
                connected.close()
                return
            }
            connected.soTimeout = 0
            socket = connected
            output = networkOutput
            frameKey = deriveKey(token)
            startCamera()
        } catch (error: Exception) {
            if (!stopping) {
                android.util.Log.e("InfinityCamera", "Camera stream failed", error)
            }
            stopSelf()
        }
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Izin kamera belum diberikan.")
        }

        val thread = HandlerThread("InfinityCameraCapture").apply { start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
            ?: throw IOException("Kamera tidak ditemukan.")

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("Konfigurasi kamera tidak tersedia.")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?: throw IOException("Format gambar kamera tidak didukung.")
        val selectedSize = chooseSize(sizes)
        val reader = ImageReader.newInstance(
            selectedSize.width,
            selectedSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        imageReader = reader
        reader.setOnImageAvailableListener(
            { source -> processLatestImage(source) },
            handler
        )

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (stopping) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                try {
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (stopping) {
                                    session.close()
                                    return
                                }
                                captureSession = session
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    session.setRepeatingRequest(request.build(), null, handler)
                                } catch (error: Exception) {
                                    android.util.Log.e("InfinityCamera", "Unable to start camera frames", error)
                                    stopSelf()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                session.close()
                                android.util.Log.e("InfinityCamera", "Camera configuration failed")
                                stopSelf()
                            }
                        },
                        handler
                    )
                } catch (error: Exception) {
                    android.util.Log.e("InfinityCamera", "Unable to configure camera", error)
                    stopSelf()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                if (!stopping) stopSelf()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                android.util.Log.e("InfinityCamera", "Camera device error: $error")
                if (!stopping) stopSelf()
            }
        }, handler)
    }

    private fun chooseSize(sizes: Array<Size>): Size {
        val eligible = sizes.filter { it.width <= 640 && it.height <= 480 }
        return (eligible.ifEmpty { sizes.toList() })
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw IOException("Ukuran kamera tidak tersedia.")
    }

    private fun processLatestImage(reader: ImageReader) {
        if (stopping) return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now

            val jpeg = imageToJpeg(image) ?: return
            if (jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES) return
            val key = frameKey ?: return
            val encrypted = encryptFrame(jpeg, key)
            if (encrypted.size > MAX_ENCRYPTED_FRAME_BYTES) return

            synchronized(outputLock) {
                val stream = output
                if (!stopping && stream != null) {
                    stream.writeInt(encrypted.size)
                    stream.write(encrypted)
                    stream.flush()
                }
            }
        } catch (error: Exception) {
            if (!stopping) {
                android.util.Log.w("InfinityCamera", "Camera frame send stopped", error)
                stopSelf()
            }
        } finally {
            image?.close()
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val nv21 = ByteArray(width * height * 3 / 2)

        val yBuffer = yPlane.buffer
        var outputIndex = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (column in 0 until width) {
                nv21[outputIndex++] = yBuffer.get(rowStart + column * yPlane.pixelStride)
            }
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (column in 0 until chromaWidth) {
                nv21[outputIndex++] = vBuffer.get(vRow + column * vPlane.pixelStride)
                nv21[outputIndex++] = uBuffer.get(uRow + column * uPlane.pixelStride)
            }
        }

        val outputBytes = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val compressed = yuv.compressToJpeg(Rect(0, 0, width, height), 55, outputBytes)
        return if (compressed) outputBytes.toByteArray() else null
    }

    private fun handshakeProof(token: String, role: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "CAMERA|$role|$challenge".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
    }

    private fun deriveKey(token: String): SecretKeySpec {
        val material = "PROJECT-INFINITY|CAMERA-AES-256-GCM|$token"
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
            if (value < 0) throw EOFException("Handshake camera stream terputus.")
            if (value == 10) return bytes.toString("UTF-8")
            if (value != 13) bytes.write(value)
        }
        throw IOException("Handshake camera stream terlalu panjang.")
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

        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        stopForeground(true)
        super.onDestroy()
    }
}
