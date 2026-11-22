package com.infinity.dual

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Cyan = Color(0xFF43D9F5)
private val Muted = Color(0xFF9BB0C8)

private data class PairingDetails(
    val host: String,
    val port: Int,
    val session: String,
    val token: String
)

private data class DeviceStats(
    val model: String,
    val androidVersion: String,
    val batteryPercent: Int?,
    val availableRamMb: Long,
    val totalRamMb: Long,
    val freeStorageMb: Long
)

private fun readDeviceStats(context: Context): DeviceStats {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memory)

    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val batteryValue = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val battery = batteryValue.takeIf { it in 0..100 }

    val storage = StatFs(Environment.getDataDirectory().path)
    return DeviceStats(
        model = Build.MODEL ?: "Perangkat Android",
        androidVersion = Build.VERSION.RELEASE ?: "Tidak diketahui",
        batteryPercent = battery,
        availableRamMb = memory.availMem / (1024L * 1024L),
        totalRamMb = memory.totalMem / (1024L * 1024L),
        freeStorageMb = storage.availableBytes / (1024L * 1024L)
    )
}

private fun parsePairingPayload(raw: String): PairingDetails? {
    val parts = raw.trim().split('|')
    if (parts.size != 6 || parts[0] != "INFINITY" || parts[1] != "1") return null

    val octets = parts[2].split('.')
    if (octets.size != 4 || octets.any {
            val value = it.toIntOrNull()
            value == null || value !in 0..255
        }) return null

    val port = parts[3].toIntOrNull() ?: return null
    if (port !in 1024..65535) return null
    if (!parts[4].matches(Regex("[A-Fa-f0-9]{8,16}"))) return null
    if (!parts[5].matches(Regex("[A-Fa-f0-9]{48}"))) return null

    return PairingDetails(parts[2], port, parts[4], parts[5])
}

@Suppress("DEPRECATION")
private fun localWifiIpv4(context: Context): String? {
    val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return null
    val address = manager.connectionInfo?.ipAddress ?: 0
    if (address == 0) return null
    return (0..3).joinToString(".") { shift ->
        ((address shr (shift * 8)) and 0xff).toString()
    }
}

private const val MAX_FILE_TRANSFER_BYTES = 8 * 1024 * 1024
private const val FILE_CHUNK_BYTES = 1024

private data class IncomingFileTransfer(
    val id: String,
    val name: String,
    val expectedSize: Long,
    val expectedSha256: String,
    val buffer: ByteArrayOutputStream,
    var nextSequence: Int = 0
)

private data class ReceivedFile(val name: String, val bytes: ByteArray)

private fun safeTransferFileName(input: String): String {
    val leaf = input.substringAfterLast('/').substringAfterLast('\\')
    return leaf.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim().take(100).ifBlank { "infinity-file" }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun readPickedFile(context: Context, uri: Uri): Pair<String, ByteArray> {
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
    }
    val name = safeTransferFileName(displayName ?: uri.lastPathSegment ?: "shared-file")
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= MAX_FILE_TRANSFER_BYTES) { "Ukuran file melebihi batas 8 MB." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("File tidak dapat dibaca.")
    return name to bytes
}

private fun createPairToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { InfinityApp() }
    }
}

@Composable
fun InfinityApp() {
    var selected by remember { mutableStateOf(0) }
    var role by remember { mutableStateOf("Master") }
    var session by remember { mutableStateOf(UUID.randomUUID().toString().take(8).uppercase()) }
    var pairingPayload by remember { mutableStateOf<String?>(null) }
    var peerIp by remember { mutableStateOf("") }
    var peerSession by remember { mutableStateOf("") }
    var peerToken by remember { mutableStateOf("") }
    var showPair by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Belum terhubung") }
    var chatInput by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<String>()) }
    var remotePadStatus by remember { mutableStateOf("Belum ada event gamepad diterima") }
    var incomingTransfer by remember { mutableStateOf<IncomingFileTransfer?>(null) }
    var pendingReceivedFile by remember { mutableStateOf<ReceivedFile?>(null) }
    var fileTransferStatus by remember { mutableStateOf("Pilih file setelah kedua HP terhubung.") }
    var fileTransferProgress by remember { mutableStateOf(0f) }
    var isSendingFile by remember { mutableStateOf(false) }
    var remoteScreenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var screenStreamStatus by remember { mutableStateOf("Layar belum dibagikan.") }
    var isSharingScreen by remember { mutableStateOf(false) }
    var remoteCameraFrame by remember { mutableStateOf<Bitmap?>(null) }
    var cameraStreamStatus by remember { mutableStateOf("Kamera belum dibagikan.") }
    var isSharingCamera by remember { mutableStateOf(false) }
    var audioStreamStatus by remember { mutableStateOf("Audio belum dibagikan.") }
    var isSharingAudio by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity
    var deviceStats by remember { mutableStateOf(readDeviceStats(context)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            deviceStats = readDeviceStats(context)
            delay(5_000)
        }
    }
    val transport = remember { LanTransport() }
    val screenStreamReceiver = remember { ScreenStreamReceiver() }
    val cameraStreamReceiver = remember { CameraStreamReceiver() }
    val audioStreamReceiver = remember { AudioStreamReceiver() }
    val scope = rememberCoroutineScope()
    val tabs = listOf("Beranda", "Hubungkan", "Tools")

    val handlePeerMessage: (String) -> Unit = { incoming ->
        val parts = incoming.split('|')
        when (parts.firstOrNull()) {
            "FILE_BEGIN" -> {
                if (parts.size == 5 && incomingTransfer == null && pendingReceivedFile == null) {
                    val id = parts[1]
                    val size = parts[3].toLongOrNull()
                    val hash = parts[4]
                    val decodedName = runCatching { String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                    if (id.matches(Regex("[A-Fa-f0-9-]{8,40}")) && size != null &&
                        size in 0..MAX_FILE_TRANSFER_BYTES.toLong() && hash.matches(Regex("[a-fA-F0-9]{64}")) &&
                        !decodedName.isNullOrBlank()) {
                        val safeName = safeTransferFileName(decodedName)
                        incomingTransfer = IncomingFileTransfer(id, safeName, size, hash.lowercase(), ByteArrayOutputStream())
                        fileTransferProgress = 0f
                        fileTransferStatus = "Menerima $safeName • 0%"
                    } else fileTransferStatus = "Transfer ditolak: metadata file tidak valid."
                } else fileTransferStatus = "Transfer ditolak: masih ada file yang sedang diproses."
            }
            "FILE_CHUNK" -> {
                val active = incomingTransfer
                if (parts.size == 4 && active != null && parts[1] == active.id) {
                    val sequence = parts[2].toIntOrNull()
                    val chunk = runCatching { Base64.decode(parts[3], Base64.DEFAULT) }.getOrNull()
                    if (sequence == active.nextSequence && chunk != null &&
                        active.buffer.size().toLong() + chunk.size <= active.expectedSize &&
                        active.buffer.size().toLong() + chunk.size <= MAX_FILE_TRANSFER_BYTES) {
                        active.buffer.write(chunk)
                        active.nextSequence += 1
                        val received = active.buffer.size()
                        fileTransferProgress = if (active.expectedSize == 0L) 1f else
                            (received.toFloat() / active.expectedSize.toFloat()).coerceIn(0f, 1f)
                        if (received % (256 * 1024) < FILE_CHUNK_BYTES || received.toLong() == active.expectedSize) {
                            fileTransferStatus = "Menerima ${active.name} • ${(fileTransferProgress * 100).toInt()}%"
                        }
                    } else {
                        incomingTransfer = null
                        fileTransferProgress = 0f
                        fileTransferStatus = "Transfer dibatalkan: urutan atau ukuran data tidak valid."
                    }
                }
            }
            "FILE_END" -> {
                val active = incomingTransfer
                if (parts.size == 2 && active != null && parts[1] == active.id) {
                    val bytes = active.buffer.toByteArray()
                    if (bytes.size.toLong() == active.expectedSize && sha256Hex(bytes).equals(active.expectedSha256, ignoreCase = true)) {
                        pendingReceivedFile = ReceivedFile(active.name, bytes)
                        fileTransferProgress = 1f
                        fileTransferStatus = "File diterima dan checksum cocok. Tekan Simpan File."
                    } else fileTransferStatus = "File gagal diverifikasi. Data tidak disimpan."
                    incomingTransfer = null
                }
            }
            "FILE_CANCEL" -> {
                incomingTransfer = null
                fileTransferProgress = 0f
                fileTransferStatus = "Pengirim membatalkan transfer."
            }
            "PAD" -> remotePadStatus = "Event dari perangkat pasangan: ${parts.drop(1).joinToString("|")}"
            "KEY" -> messages = messages + "Keyboard peer: ${incoming.removePrefix("KEY|")}"
            else -> messages = messages + "Peer: $incoming"
        }
    }
    val sendPadCommand: (String) -> Unit = { command ->
        scope.launch {
            if (transport.send("PAD|$command")) {
                remotePadStatus = "Event dikirim: $command"
            } else {
                remotePadStatus = "Belum terhubung. Hubungkan perangkat dahulu."
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scan = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val raw = scan?.contents
        val details = raw?.let(::parsePairingPayload)
        if (details == null) {
            status = if (raw == null) "Pemindaian dibatalkan" else "QR tidak dikenali. Gunakan QR dari PROJECT INFINITY."
        } else {
            peerIp = details.host
            peerSession = details.session
            peerToken = details.token
            status = "QR valid • Master ${details.host} • sesi ${details.session}"
        }
    }

    val screenConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            if (role != "Client" || peerIp.isBlank() || peerToken.isBlank() || !status.startsWith("Terhubung")) {
                screenStreamStatus = "Hubungkan sebagai Client ke Master sebelum membagikan layar."
            } else {
                val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
                    putExtra(ScreenCaptureService.EXTRA_HOST, peerIp)
                    putExtra(ScreenCaptureService.EXTRA_TOKEN, peerToken)
                }
                try {
                    context.startForegroundService(captureIntent)
                    isSharingScreen = true
                    screenStreamStatus = "Memulai screen share ke " + peerIp + "..."
                } catch (error: Exception) {
                    screenStreamStatus = error.message ?: "Gagal memulai screen share."
                    isSharingScreen = false
                }
            }
        } else {
            screenStreamStatus = "Izin screen capture dibatalkan."
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (role == "Client" && peerIp.isNotBlank() && peerToken.isNotBlank() && status.startsWith("Terhubung")) {
                try {
                    val cameraIntent = Intent(context, CameraCaptureService::class.java).apply {
                        action = CameraCaptureService.ACTION_START
                        putExtra(CameraCaptureService.EXTRA_HOST, peerIp)
                        putExtra(CameraCaptureService.EXTRA_TOKEN, peerToken)
                    }
                    context.startForegroundService(cameraIntent)
                    isSharingCamera = true
                    cameraStreamStatus = "Memulai camera stream ke $peerIp..."
                } catch (error: Exception) {
                    isSharingCamera = false
                    cameraStreamStatus = error.message ?: "Gagal memulai camera stream."
                }
            } else {
                cameraStreamStatus = "Hubungkan sebagai Client ke Master sebelum membagikan kamera."
            }
        } else {
            cameraStreamStatus = "Izin kamera ditolak. Streaming tidak dimulai."
        }
    }

    val startCameraShare: () -> Unit = {
        if (role != "Client" || peerIp.isBlank() || peerToken.isBlank() || !status.startsWith("Terhubung")) {
            cameraStreamStatus = "Hubungkan sebagai Client ke Master sebelum membagikan kamera."
        } else if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            try {
                val cameraIntent = Intent(context, CameraCaptureService::class.java).apply {
                    action = CameraCaptureService.ACTION_START
                    putExtra(CameraCaptureService.EXTRA_HOST, peerIp)
                    putExtra(CameraCaptureService.EXTRA_TOKEN, peerToken)
                }
                context.startForegroundService(cameraIntent)
                isSharingCamera = true
                cameraStreamStatus = "Memulai camera stream ke $peerIp..."
            } catch (error: Exception) {
                isSharingCamera = false
                cameraStreamStatus = error.message ?: "Gagal memulai camera stream."
            }
        }
    }

    val stopCameraShare: () -> Unit = {
        context.stopService(Intent(context, CameraCaptureService::class.java))
        isSharingCamera = false
        cameraStreamStatus = "Camera streaming dihentikan."
    }

    val launchAudioCapture: () -> Unit = {
        try {
            val audioIntent = Intent(context, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_HOST, peerIp)
                putExtra(AudioCaptureService.EXTRA_TOKEN, peerToken)
            }
            context.startForegroundService(audioIntent)
            isSharingAudio = true
            audioStreamStatus = "Memulai audio stream ke $peerIp..."
        } catch (error: Exception) {
            isSharingAudio = false
            audioStreamStatus = error.message ?: "Gagal memulai audio stream."
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchAudioCapture()
        } else {
            audioStreamStatus = "Izin mikrofon ditolak. Audio streaming tidak dimulai."
        }
    }

    val startAudioShare: () -> Unit = {
        if (role != "Client" || peerIp.isBlank() || peerToken.isBlank() || !status.startsWith("Terhubung")) {
            audioStreamStatus = "Hubungkan sebagai Client ke Master sebelum membagikan mikrofon."
        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchAudioCapture()
        }
    }

    val stopAudioShare: () -> Unit = {
        context.stopService(Intent(context, AudioCaptureService::class.java))
        isSharingAudio = false
        audioStreamStatus = "Audio streaming dihentikan."
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isSendingFile = true
                fileTransferProgress = 0f
                fileTransferStatus = "Membaca file..."
                var transferId: String? = null
                try {
                    val (name, bytes) = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
                    val id = UUID.randomUUID().toString()
                    transferId = id
                    val encodedName = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val checksum = sha256Hex(bytes)
                    check(transport.send("FILE_BEGIN|$id|$encodedName|${bytes.size}|$checksum")) {
                        "Tidak ada koneksi aktif ke perangkat pasangan."
                    }
                    fileTransferStatus = "Mengirim $name • 0%"
                    var sequence = 0
                    for (offset in bytes.indices step FILE_CHUNK_BYTES) {
                        val end = minOf(offset + FILE_CHUNK_BYTES, bytes.size)
                        val chunk = bytes.copyOfRange(offset, end)
                        val encodedChunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        check(transport.send("FILE_CHUNK|$id|$sequence|$encodedChunk")) {
                            "Koneksi terputus saat mengirim file."
                        }
                        sequence += 1
                        fileTransferProgress = end.toFloat() / bytes.size.coerceAtLeast(1).toFloat()
                        if (sequence % 32 == 0 || end == bytes.size) {
                            fileTransferStatus = "Mengirim $name • ${(fileTransferProgress * 100).toInt()}%"
                        }
                    }
                    check(transport.send("FILE_END|$id")) { "Koneksi terputus sebelum transfer selesai." }
                    fileTransferProgress = 1f
                    fileTransferStatus = "Pengiriman $name selesai. Menunggu verifikasi di HP penerima."
                } catch (error: Exception) {
                    transferId?.let { transport.send("FILE_CANCEL|$it") }
                    fileTransferStatus = error.message ?: "Transfer file gagal."
                } finally {
                    isSendingFile = false
                }
            }
        }
    }

    val saveReceivedLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val received = pendingReceivedFile
        if (uri != null && received != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(received.bytes)
                            output.flush()
                        } ?: throw IOException("Lokasi penyimpanan tidak dapat dibuka.")
                    }
                    fileTransferStatus = "File ${received.name} berhasil disimpan."
                    pendingReceivedFile = null
                } catch (error: Exception) {
                    fileTransferStatus = error.message ?: "Gagal menyimpan file."
                }
            }
        }
    }
    DisposableEffect(transport, screenStreamReceiver, cameraStreamReceiver, audioStreamReceiver) {
        onDispose {
            transport.close()
            screenStreamReceiver.close()
            cameraStreamReceiver.close()
            audioStreamReceiver.close()
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            context.stopService(Intent(context, CameraCaptureService::class.java))
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Cyan, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Navy, bottomBar = {
            NavigationBar(containerColor = Panel) {
                val icons = listOf(Icons.Default.Dashboard, Icons.Default.Link, Icons.Default.Tune)
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(icons[index], contentDescription = label) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan,
                            indicatorColor = Cyan.copy(alpha = .15f),
                            selectedTextColor = Cyan
                        )
                    )
                }
            }
        }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
                contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PROJECT", color = Muted, fontSize = 12.sp, letterSpacing = 3.sp)
                            Text("INFINITY", fontSize = 29.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                        Surface(color = Cyan.copy(alpha = .12f), shape = RoundedCornerShape(20.dp)) {
                            Text("v1.5.0 • AUDIO STREAM", color = Cyan, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                when (selected) {
                    0 -> {
                        item { HeroCard(status) }
                        item { Text("DEVICE OVERVIEW", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold) }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard("ROLE", role, Icons.Default.Devices, Modifier.weight(1f))
                                MetricCard("LINK", if (status.startsWith("Terhubung")) "Online" else "Offline", Icons.Default.Wifi, Modifier.weight(1f))
                            }
                        }
                        item { FeatureCard("Ekosistem dua perangkat", "Pairing QR tervalidasi, pesan LAN, dan fondasi untuk berbagi file serta kontrol perangkat.", Icons.Default.Hub) }
                        item { FeatureCard("Koneksi lokal", "Kedua HP harus berada pada Wi-Fi atau hotspot yang sama. Komunikasi lokal tidak membutuhkan internet.", Icons.Default.Wifi) }
                    }
                    1 -> {
                        item { Text("DEVICE PAIRING", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp) }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(
                                    Modifier.fillMaxWidth().padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Pilih peran perangkat", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = role == "Master",
                                            onClick = { role = "Master" },
                                            label = { Text("Master") }
                                        )
                                        FilterChip(
                                            selected = role == "Client",
                                            onClick = {
                                                role = "Client"
                                                pairingPayload = null
                                            },
                                            label = { Text("Client") }
                                        )
                                    }

                                    Text("Kode sesi", color = Muted)
                                    Text(session, color = Cyan, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)

                                    if (role == "Master") {
                                        if (pairingPayload == null) {
                                            Text("Tekan Host untuk membuat QR pairing yang berisi alamat LAN dan token sesi sementara.", color = Muted, fontSize = 12.sp)
                                        } else {
                                            QrImage(pairingPayload!!)
                                            Text("Tampilkan QR ini kepada HP Client. Token hanya berlaku untuk sesi Host saat ini.", color = Muted, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text("Pindai QR dari perangkat Master untuk mengisi alamat host dan kredensial sesi.", color = Muted, fontSize = 12.sp)
                                        OutlinedButton(
                                            onClick = {
                                                val hostActivity = activity
                                                if (hostActivity == null) {
                                                    status = "Pemindai tidak tersedia pada konteks ini"
                                                } else {
                                                    val intent = IntentIntegrator(hostActivity)
                                                        .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                                                        .setPrompt("Arahkan kamera ke QR PROJECT INFINITY pada HP Master")
                                                        .setBeepEnabled(false)
                                                        .setOrientationLocked(false)
                                                        .createScanIntent()
                                                    scanLauncher.launch(intent)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.QrCodeScanner, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Pindai QR Master")
                                        }
                                    }

                                    if (role == "Client") {
                                        OutlinedTextField(
                                            value = peerIp,
                                            onValueChange = { peerIp = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("IP perangkat Master") },
                                            placeholder = { Text("Terisi setelah QR dipindai") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                        )
                                        Text(
                                            if (peerSession.isNotBlank()) "Sesi terbaca: $peerSession" else "Belum ada data pairing",
                                            color = Muted,
                                            fontSize = 12.sp
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (role == "Master") {
                                            Button(
                                                onClick = {
                                                    val hostIp = localWifiIpv4(context)
                                                    if (hostIp == null) {
                                                        status = "Alamat Wi-Fi belum tersedia. Hubungkan HP ke Wi-Fi/hotspot terlebih dahulu."
                                                    } else {
                                                        transport.close()
                                                        session = UUID.randomUUID().toString().take(8).uppercase()
                                                        val token = createPairToken()
                                                        pairingPayload = "INFINITY|1|$hostIp|${LanTransport.PORT}|$session|$token"
                                                        status = "Menyiapkan Host di $hostIp:${LanTransport.PORT}"
                                                        remoteScreenFrame = null
                                                        screenStreamReceiver.close()
                                                        cameraStreamReceiver.close()
                                                        audioStreamReceiver.close()
                                                        remoteCameraFrame = null
                                                        cameraStreamStatus = "Menunggu Client membagikan kamera."
                                                        audioStreamStatus = "Menunggu Client membagikan mikrofon."
                                                        scope.launch {
                                                            screenStreamReceiver.listen(
                                                                token,
                                                                { frame -> remoteScreenFrame = frame },
                                                                { streamMessage -> screenStreamStatus = streamMessage }
                                                            )
                                                        }
                                                        scope.launch {
                                                            cameraStreamReceiver.listen(
                                                                token,
                                                                { frame -> remoteCameraFrame = frame },
                                                                { streamMessage -> cameraStreamStatus = streamMessage }
                                                            )
                                                        }
                                                        scope.launch {
                                                            audioStreamReceiver.listen(
                                                                token,
                                                                { streamMessage -> audioStreamStatus = streamMessage }
                                                            )
                                                        }
                                                        scope.launch {
                                                            transport.host(session, token, { status = it }, { handlePeerMessage(it) })
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Host / Buat QR") }
                                        } else {
                                            Button(
                                                onClick = {
                                                    if (peerIp.isNotBlank() && peerSession.isNotBlank() && peerToken.isNotBlank()) {
                                                        transport.close()
                                                        scope.launch {
                                                            transport.connect(
                                                                peerIp, peerSession, peerToken,
                                                                { status = it },
                                                                { handlePeerMessage(it) }
                                                            )
                                                        }
                                                    } else {
                                                        status = "Pindai QR Master terlebih dahulu"
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                enabled = peerIp.isNotBlank() && peerSession.isNotBlank() && peerToken.isNotBlank()
                                            ) { Text("Connect") }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                transport.close()
                                                screenStreamReceiver.close()
                                                cameraStreamReceiver.close()
                                                audioStreamReceiver.close()
                                                context.stopService(Intent(context, ScreenCaptureService::class.java))
                                                context.stopService(Intent(context, CameraCaptureService::class.java))
                                                context.stopService(Intent(context, AudioCaptureService::class.java))
                                                isSharingScreen = false
                                                isSharingCamera = false
                                                isSharingAudio = false
                                                remoteScreenFrame = null
                                                remoteCameraFrame = null
                                                cameraStreamStatus = "Camera stream dihentikan."
                                                audioStreamStatus = "Audio stream dihentikan."
                                                pairingPayload = null
                                                peerSession = ""
                                                peerToken = ""
                                                status = "Koneksi ditutup"
                                                screenStreamStatus = "Screen stream dihentikan."
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Putuskan") }
                                    }

                                    Text(status, color = Cyan, fontSize = 12.sp)
                                    Divider(color = Muted.copy(alpha = .25f))
                                    Text("PESAN ANTARPERANGKAT", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    OutlinedTextField(
                                        value = chatInput,
                                        onValueChange = { chatInput = it.take(2000) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Tulis pesan") },
                                        singleLine = true
                                    )
                                    Button(
                                        onClick = {
                                            val outgoing = chatInput.trim()
                                            if (outgoing.isNotEmpty()) {
                                                scope.launch {
                                                    if (transport.send(outgoing)) messages = messages + "Saya: $outgoing"
                                                    else messages = messages + "Sistem: belum ada koneksi aktif"
                                                    chatInput = ""
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Kirim pesan") }
                                    messages.takeLast(6).forEach { Text(it, color = Muted, fontSize = 12.sp) }

                                    Text(
                                        "Keamanan LAN: pairing memakai challenge-response; pesan, transfer file, frame screen, camera, dan audio streaming dilindungi AES-256-GCM.",
                                        color = Muted,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        item { Text("INFINITY TOOLS", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp) }
                        item {
                            FileTransferCard(
                                status = fileTransferStatus,
                                progress = fileTransferProgress,
                                isSending = isSendingFile,
                                isConnected = status.startsWith("Terhubung"),
                                incomingName = pendingReceivedFile?.name,
                                incomingSize = pendingReceivedFile?.bytes?.size,
                                onPickFile = { pickFileLauncher.launch(arrayOf("*/*")) },
                                onSaveIncoming = { pendingReceivedFile?.let { saveReceivedLauncher.launch(it.name) } }
                            )
                        }
                        item { TouchpadRelayCard(remotePadStatus, sendPadCommand) }
                        item { KeyboardRelayCard { text -> sendPadCommand("KEY|$text") } }
                        item { FeatureCard("Remote Input", "Event touchpad, gamepad, dan teks dikirim ke aplikasi pasangan. Tidak mengendalikan aplikasi eksternal.", Icons.Default.Gamepad) }
                        item { SystemMonitorCard(deviceStats) }
                        item { GamepadRelayCard(remotePadStatus, sendPadCommand) }
                        item {
                            ScreenStreamCard(
                                role = role,
                                isConnected = status.startsWith("Terhubung"),
                                remoteFrame = remoteScreenFrame,
                                status = screenStreamStatus,
                                isSharing = isSharingScreen,
                                onStartShare = {
                                    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    screenConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
                                },
                                onStopShare = {
                                    context.stopService(Intent(context, ScreenCaptureService::class.java))
                                    isSharingScreen = false
                                    screenStreamStatus = "Screen share dihentikan."
                                }
                            )
                        }
                        item {
                            CameraStreamCard(
                                role = role,
                                isConnected = status.startsWith("Terhubung"),
                                remoteFrame = remoteCameraFrame,
                                status = cameraStreamStatus,
                                isSharing = isSharingCamera,
                                onStartShare = startCameraShare,
                                onStopShare = stopCameraShare
                            )
                        }
                        item {
                            AudioStreamCard(
                                role = role,
                                isConnected = status.startsWith("Terhubung"),
                                status = audioStreamStatus,
                                isSharing = isSharingAudio,
                                onStartShare = startAudioShare,
                                onStopShare = stopAudioShare
                            )
                        }
                    }
                }
            }
        }
        if (showPair) AlertDialog(
            onDismissRequest = { showPair = false },
            title = { Text("Cara menghubungkan") },
            text = { Text("1. Pasang aplikasi pada kedua HP.\n2. Hubungkan keduanya ke Wi-Fi atau hotspot yang sama.\n3. Pada HP pertama pilih Master lalu tekan Host / Buat QR.\n4. Pada HP kedua pilih Client, pindai QR Master, lalu tekan Connect.\n5. Setelah status Terhubung muncul, uji fitur pesan.\n\nPairing memerlukan jaringan lokal yang mengizinkan koneksi antarperangkat.") },
            confirmButton = { TextButton(onClick = { showPair = false }) { Text("Mengerti") } }
        )
    }
}




@Composable
private fun ScreenStreamCard(
    role: String,
    isConnected: Boolean,
    remoteFrame: Bitmap?,
    status: String,
    isSharing: Boolean,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text("LAN SCREEN STREAM", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("Streaming layar satu arah dari Client ke Master. Resolusi dibatasi hingga sisi terpanjang 960 px dan sekitar 8 FPS.", color = Muted, fontSize = 12.sp)
            if (remoteFrame != null) {
                Image(
                    bitmap = remoteFrame.asImageBitmap(),
                    contentDescription = "Pratinjau layar dari perangkat Client",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF0B1727), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (role == "Master") "Pratinjau layar pasangan muncul di sini." else "Layar yang dibagikan akan tampil pada HP Master.", color = Muted, fontSize = 12.sp)
                }
            }
            if (role == "Client") {
                Button(onClick = onStartShare, enabled = isConnected && !isSharing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ScreenShare, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Bagikan layar • minta izin Android")
                }
                if (isSharing) {
                    OutlinedButton(onClick = onStopShare, modifier = Modifier.fillMaxWidth()) { Text("Hentikan screen share") }
                }
            } else {
                Text("HP Master menerima layar dari Client setelah QR pairing aktif. Tekan Stop dari HP Client untuk mengakhiri.", color = Muted, fontSize = 11.sp)
            }
            Text(status, color = Cyan, fontSize = 12.sp)
            Text("Android menampilkan dialog izin dan notifikasi permanen selama capture. Frame layar dienkripsi AES-GCM saat dikirim melalui LAN. Audio belum disertakan.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun CameraStreamCard(
    role: String,
    isConnected: Boolean,
    remoteFrame: Bitmap?,
    status: String,
    isSharing: Boolean,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text("LAN CAMERA STREAM", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Streaming kamera satu arah dari Client ke Master. Resolusi dibatasi hingga sekitar 640×480 dan 5 FPS untuk mengurangi beban jaringan.",
                color = Muted,
                fontSize = 12.sp
            )
            if (remoteFrame != null) {
                Image(
                    bitmap = remoteFrame.asImageBitmap(),
                    contentDescription = "Pratinjau kamera dari perangkat Client",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF0B1727), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (role == "Master") "Pratinjau kamera Client muncul di sini."
                        else "Kamera akan dibagikan ke HP Master setelah izin diberikan.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }
            if (role == "Client") {
                Button(
                    onClick = onStartShare,
                    enabled = isConnected && !isSharing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mulai camera stream • minta izin")
                }
                if (isSharing) {
                    OutlinedButton(onClick = onStopShare, modifier = Modifier.fillMaxWidth()) {
                        Text("Hentikan camera stream")
                    }
                }
            } else {
                Text(
                    "HP Master menerima gambar dari Client setelah QR pairing aktif. Camera stream hanya aktif setelah pengguna Client memberi izin kamera dan menekan Mulai.",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
            Text(status, color = Cyan, fontSize = 12.sp)
            Text(
                "Android meminta izin kamera dan menampilkan notifikasi permanen selama capture. Frame dienkripsi AES-GCM. Kamera berhenti saat tombol Stop ditekan atau sesi ditutup.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun AudioStreamCard(
    role: String,
    isConnected: Boolean,
    status: String,
    isSharing: Boolean,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text("LAN AUDIO STREAM", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Audio satu arah dari mikrofon Client ke speaker Master. Format mono PCM 16 kHz untuk penggunaan jaringan lokal.",
                color = Muted,
                fontSize = 12.sp
            )
            if (role == "Client") {
                Button(
                    onClick = onStartShare,
                    enabled = isConnected && !isSharing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mulai audio stream • minta izin")
                }
                if (isSharing) {
                    OutlinedButton(onClick = onStopShare, modifier = Modifier.fillMaxWidth()) {
                        Text("Hentikan audio stream")
                    }
                }
            } else {
                Text(
                    "HP Master memutar suara yang dikirim Client setelah pengguna Client memberi izin mikrofon dan menekan Mulai.",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
            Text(status, color = Cyan, fontSize = 12.sp)
            Text(
                "Audio berasal dari mikrofon, bukan audio internal aplikasi. Android meminta izin RECORD_AUDIO dan menampilkan notifikasi permanen selama mikrofon digunakan. Tekan Stop untuk mengakhiri.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun FileTransferCard(
    status: String,
    progress: Float,
    isSending: Boolean,
    isConnected: Boolean,
    incomingName: String?,
    incomingSize: Int?,
    onPickFile: () -> Unit,
    onSaveIncoming: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text("LAN FILE TRANSFER", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("Kirim file langsung antar-HP melalui Wi-Fi lokal. Maksimal 8 MB per file.", color = Muted, fontSize = 12.sp)
            Button(onClick = onPickFile, enabled = isConnected && !isSending && incomingName == null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isSending) "Sedang mengirim..." else "Pilih & kirim file")
            }
            if (isSending || progress > 0f) {
                LinearProgressIndicator(progress = progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            }
            Text(status, color = Cyan, fontSize = 12.sp, lineHeight = 17.sp)
            if (incomingName != null && incomingSize != null) {
                Divider(color = Muted.copy(alpha = .25f))
                Text("File masuk siap disimpan", color = Color.White, fontWeight = FontWeight.Bold)
                Text("$incomingName • $incomingSize byte", color = Muted, fontSize = 12.sp)
                Button(onClick = onSaveIncoming, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Simpan File")
                }
            }
            Text("Penerima memilih sendiri lokasi simpan. Data dilindungi AES-256-GCM saat dikirim dan diperiksa dengan SHA-256 sebelum disimpan. File sementara dibatasi 8 MB.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
@Composable
private fun GamepadRelayCard(status: String, onCommand: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Gamepad, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(10.dp))
                Text("DUAL DEVICE GAMEPAD", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("D-Pad", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                GamepadButton("▲", "UP", onCommand)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                GamepadButton("◀", "LEFT", onCommand)
                Spacer(Modifier.width(8.dp))
                GamepadButton("OK", "CENTER", onCommand)
                Spacer(Modifier.width(8.dp))
                GamepadButton("▶", "RIGHT", onCommand)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                GamepadButton("▼", "DOWN", onCommand)
            }
            Divider(color = Muted.copy(alpha = .25f))
            Text("Action buttons", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("A", "B", "X", "Y").forEach { key ->
                    OutlinedButton(
                        onClick = { onCommand(key) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text(key, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(status, color = Cyan, fontSize = 12.sp)
            Text(
                "Tombol mengirim event ke aplikasi PROJECT INFINITY di perangkat pasangan. Ini belum menjadi gamepad HID dan tidak mengendalikan game eksternal secara otomatis.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun GamepadButton(label: String, command: String, onCommand: (String) -> Unit) {
    OutlinedButton(
        onClick = { onCommand(command) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun KeyboardRelayCard(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TEXT RELAY KEYBOARD", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            OutlinedTextField(value = text, onValueChange = { text = it.take(500) }, modifier = Modifier.fillMaxWidth(), label = { Text("Teks untuk perangkat pasangan") }, minLines = 2)
            Button(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Kirim teks") }
            Text("Teks ditampilkan di dalam aplikasi pasangan; bukan mengetik otomatis pada aplikasi lain.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TouchpadRelayCard(status: String, onCommand: (String) -> Unit) {
    var lastX by remember { mutableStateOf(0f) }
    var lastY by remember { mutableStateOf(0f) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TouchApp, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(8.dp))
                Text("LAN TOUCHPAD", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Box(
                Modifier.fillMaxWidth().height(190.dp).background(Color(0xFF0B1727), RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onCommand("TOUCH|DOWN") },
                            onDragEnd = { onCommand("TOUCH|UP") },
                            onDragCancel = { onCommand("TOUCH|CANCEL") }
                        ) { change, dragAmount ->
                            change.consume()
                            lastX = (lastX + dragAmount.x / size.width).coerceIn(0f, 1f)
                            lastY = (lastY + dragAmount.y / size.height).coerceIn(0f, 1f)
                            onCommand("TOUCH|MOVE|${(lastX * 1000).toInt()}|${(lastY * 1000).toInt()}")
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("Geser di area ini\nuntuk mengirim koordinat touchpad", color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCommand("TOUCH|CLICK") }, modifier = Modifier.weight(1f)) { Text("Klik kiri") }
                OutlinedButton(onClick = { onCommand("TOUCH|RIGHT") }, modifier = Modifier.weight(1f)) { Text("Klik kanan") }
            }
            Text(status, color = Cyan, fontSize = 12.sp)
            Text("Touchpad ini hanya mengirim event ke aplikasi PROJECT INFINITY di HP pasangan, bukan mengendalikan layar Android atau game eksternal.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun SystemMonitorCard(stats: DeviceStats) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MonitorHeart, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("SYSTEM MONITOR", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(stats.model, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Android ${stats.androidVersion} • pembaruan otomatis setiap 5 detik", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("BATTERY", stats.batteryPercent?.let { "$it%" } ?: "N/A", Icons.Default.BatteryChargingFull, Modifier.weight(1f))
                MetricCard("RAM FREE", "${stats.availableRamMb} MB", Icons.Default.Memory, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("RAM TOTAL", "${stats.totalRamMb} MB", Icons.Default.DeveloperBoard, Modifier.weight(1f))
                MetricCard("STORAGE FREE", "${stats.freeStorageMb} MB", Icons.Default.Storage, Modifier.weight(1f))
            }
            Text(
                "Angka RAM adalah informasi memori sistem yang dilaporkan Android. Monitor ini tidak mengubah frekuensi CPU/GPU atau performa perangkat.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun HeroCard(status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = Cyan, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("DUAL DEVICE ECOSYSTEM", color = Cyan, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            }
            Text("Two phones.\nOne connected workspace.", fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
            Text("Hubungkan perangkat dan kelola tools dari satu dashboard.", color = Muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (status.startsWith("Terhubung")) Color.Green else Color(0xFFFFB74D), RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(status, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, tint = Cyan, contentDescription = null)
            Text(title, color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, tint = Cyan, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(13.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun QrImage(value: String) {
    val bitmap = remember(value) { createQr(value) }
    if (bitmap != null) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        ) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "QR code pairing PROJECT INFINITY",
                modifier = Modifier.padding(12.dp).size(220.dp)
            )
        }
    }
}

private fun createQr(value: String): Bitmap? = try {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480)
    Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until 480) for (y in 0 until 480) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
        }
    }
} catch (_: Exception) {
    null
}
