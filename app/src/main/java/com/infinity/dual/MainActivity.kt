package com.infinity.dual

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Cyan = Color(0xFF43D9F5)
private val Muted = Color(0xFF9BB0C8)

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
    var showPair by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Belum terhubung") }
    var peerIp by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<String>()) }
    val transport = remember { LanTransport() }
    val scope = rememberCoroutineScope()
    val tabs = listOf("Beranda", "Hubungkan", "Tools")

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
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, indicatorColor = Cyan.copy(alpha = .15f), selectedTextColor = Cyan)
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
                            Text("v1.0.0 • MAX", color = Cyan, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                                MetricCard("LINK", if (status == "Terhubung") "Online" else "Offline", Icons.Default.Wifi, Modifier.weight(1f))
                            }
                        }
                        item { FeatureCard("Ekosistem dua perangkat", "Pairing, berbagi file, remote input, dan monitoring dalam satu tempat.", Icons.Default.Hub) }
                        item { FeatureCard("Koneksi lokal", "Dirancang untuk jaringan Wi-Fi/hotspot lokal. Internet tidak diperlukan untuk komunikasi lokal setelah kedua perangkat berada di jaringan yang sama.", Icons.Default.Wifi) }
                    }
                    1 -> {
                        item { Text("DEVICE PAIRING", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp) }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Pilih peran perangkat", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = role == "Master", onClick = { role = "Master" }, label = { Text("Master") })
                                        FilterChip(selected = role == "Client", onClick = { role = "Client" }, label = { Text("Client") })
                                    }
                                    Text("Kode sesi", color = Muted)
                                    Text(session, color = Cyan, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                                    QrImage("INFINITY:$session:$role")
                                    Text("QR sesi masih berupa identitas visual. Koneksi LAN dasar tersedia di bawah; kedua HP harus berada pada Wi-Fi/hotspot yang sama.", color = Muted, fontSize = 12.sp)
                                    OutlinedButton(onClick = { session = UUID.randomUUID().toString().take(8).uppercase(); status = "Sesi baru dibuat" }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Buat sesi baru")
                                    }
                                    Button(onClick = { showPair = true }, modifier = Modifier.fillMaxWidth()) { Text("Petunjuk menghubungkan") }
                                    Divider(color = Muted.copy(alpha = .25f))
                                    Text("KONEKSI WI-FI LOKAL", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("HP Master: tekan Host. HP Client: masukkan alamat IP Master lalu tekan Connect. Port 47821.", color = Muted, fontSize = 12.sp)
                                    OutlinedTextField(value = peerIp, onValueChange = { peerIp = it }, modifier = Modifier.fillMaxWidth(), label = { Text("IP perangkat Master") }, placeholder = { Text("Contoh: 192.168.1.10") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { transport.close(); scope.launch { transport.host({ status = it }, { messages = messages + "Peer: $it" }) } }, modifier = Modifier.weight(1f)) { Text("Host") }
                                        OutlinedButton(onClick = { if (peerIp.isNotBlank()) { transport.close(); scope.launch { transport.connect(peerIp, { status = it }, { messages = messages + "Peer: $it" }) } } }, modifier = Modifier.weight(1f), enabled = peerIp.isNotBlank()) { Text("Connect") }
                                    }
                                    Text(status, color = Cyan, fontSize = 12.sp)
                                    OutlinedTextField(value = chatInput, onValueChange = { chatInput = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pesan antarperangkat") }, singleLine = true)
                                    Button(onClick = { val outgoing = chatInput.trim(); if (outgoing.isNotEmpty()) scope.launch { if (transport.send(outgoing)) messages = messages + "Saya: $outgoing" else messages = messages + "Sistem: belum ada koneksi aktif"; chatInput = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Kirim pesan") }
                                    messages.takeLast(5).forEach { Text(it, color = Muted, fontSize = 12.sp) }
                                    OutlinedButton(onClick = { transport.close(); status = "Koneksi ditutup" }, modifier = Modifier.fillMaxWidth()) { Text("Putuskan koneksi") }
                                }
                            }
                        }
                    }
                    else -> {
                        item { Text("INFINITY TOOLS", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp) }
                        item { FeatureCard("File Transfer", "Direncanakan: kirim file dengan progress, resume, dan verifikasi checksum.", Icons.Default.Folder) }
                        item { FeatureCard("Remote Input", "Direncanakan: touchpad, keyboard, dan gamepad virtual dengan izin eksplisit.", Icons.Default.Gamepad) }
                        item { FeatureCard("System Monitor", "Menampilkan informasi perangkat yang diizinkan Android, seperti RAM, baterai, dan jaringan.", Icons.Default.MonitorHeart) }
                        item { FeatureCard("Screen & Audio Stream", "Streaming layar dan audio memerlukan izin tangkap layar Android pada perangkat sumber.", Icons.Default.Cast) }
                        item { FeatureCard("Camera Link", "Akses kamera hanya setelah izin kamera diberikan di perangkat yang menjadi kamera.", Icons.Default.PhotoCamera) }
                    }
                }
            }
        }
        if (showPair) AlertDialog(
            onDismissRequest = { showPair = false },
            title = { Text("Cara menghubungkan") },
            text = { Text("1. Pasang aplikasi ini pada kedua HP.\n2. Hubungkan keduanya ke Wi-Fi atau hotspot yang sama.\n3. Pilih Master pada satu HP dan Client pada HP lainnya.\n4. Cocokkan kode sesi.\n\nPairing QR pada versi ini masih berupa prototipe visual; transport jaringan dan pemindaian QR akan ditambahkan pada iterasi berikutnya.") },
            confirmButton = { TextButton(onClick = { showPair = false }) { Text("Mengerti") } }
        )
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
                Box(Modifier.size(8.dp).background(if (status == "Terhubung") Color.Green else Color(0xFFFFB74D), RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(status, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, tint = Cyan, contentDescription = null)
            Text(title, color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun FeatureCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
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
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.alignCenter()) {
            Image(bitmap.asImageBitmap(), contentDescription = "QR code sesi", modifier = Modifier.padding(12.dp).size(190.dp))
        }
    }
}

private fun Modifier.alignCenter(): Modifier = this.wrapContentWidth(Alignment.CenterHorizontally)

private fun createQr(value: String): Bitmap? = try {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 420, 420)
    Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888).also { bmp ->
        for (x in 0 until 420) for (y in 0 until 420) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
} catch (_: Exception) { null }
