package com.infinity.dual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Receives a one-way JPEG screen stream from a paired client.
 * The short-lived QR token authenticates the stream handshake; frames are not encrypted.
 */
class ScreenStreamReceiver {
    companion object {
        const val PORT = 47822
        private const val MAX_FRAME_BYTES = 1_500_000
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var peer: Socket? = null
    @Volatile private var closed = false

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
                onStatus("Menunggu screen share pada port $PORT")
            }

            val connected = created.accept()
            client = connected
            peer = connected
            connected.tcpNoDelay = true
            connected.soTimeout = 15_000

            val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
            val hello = readProtocolLine(input)
            val expected = "STREAM|" + token
            if (!MessageDigest.isEqual(
                    hello.toByteArray(Charsets.UTF_8),
                    expected.toByteArray(Charsets.UTF_8)
                )
            ) {
                throw IOException("Autentikasi screen stream ditolak.")
            }

            connected.getOutputStream().apply {
                write("OK\n".toByteArray(Charsets.UTF_8))
                flush()
            }
            connected.soTimeout = 0

            while (!closed && !connected.isClosed) {
                val frameSize = input.readInt()
                if (frameSize !in 1..MAX_FRAME_BYTES) {
                    throw IOException("Ukuran frame tidak valid.")
                }
                val bytes = ByteArray(frameSize)
                input.readFully(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: continue
                withContext(Dispatchers.Main) {
                    if (!closed) {
                        onFrame(bitmap)
                        onStatus("Screen streaming aktif")
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
