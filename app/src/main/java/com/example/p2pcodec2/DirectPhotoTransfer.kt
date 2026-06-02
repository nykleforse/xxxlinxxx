package com.example.p2pcodec2

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.util.UUID

class DirectPhotoTransfer(
    private val context: Context,
    private val sendPacket: (String) -> Boolean,
    private val onPhotoReceived: (String, String) -> Unit,
    private val onPhotoAck: (String) -> Unit
) {
    private val incoming = mutableMapOf<String, IncomingPhoto>()

    fun sendPhoto(id: String, uri: Uri): Boolean {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val fileName = displayName(uri).takeIf { it.isNotBlank() } ?: "photo-${UUID.randomUUID()}.jpg"
        val meta = listOf(PREFIX_META, id, b64(fileName.toByteArray(Charsets.UTF_8)), bytes.size.toString())
            .joinToString("|")
        if (!sendPacket(meta)) return false

        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val packet = listOf(PREFIX_CHUNK, id, index.toString(), b64(chunk)).joinToString("|")
            if (!sendPacket(packet)) return false
            offset = end
            index += 1
        }
        return sendPacket(listOf(PREFIX_DONE, id, index.toString()).joinToString("|"))
    }

    fun handlePacket(packet: String): Boolean {
        val parts = packet.split("|", limit = 4)
        return when (parts.firstOrNull()) {
            PREFIX_META -> {
                val id = parts.getOrNull(1) ?: return true
                val name = parts.getOrNull(2)?.let { decodeText(it) } ?: "photo.jpg"
                val size = parts.getOrNull(3)?.toIntOrNull() ?: 0
                incoming[id] = IncomingPhoto(name, size)
                true
            }
            PREFIX_CHUNK -> {
                val id = parts.getOrNull(1) ?: return true
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return true
                val chunk = parts.getOrNull(3)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return true
                val photo = incoming[id] ?: IncomingPhoto("photo.jpg", 0).also { incoming[id] = it }
                photo.chunks[index] = chunk
                true
            }
            PREFIX_DONE -> {
                val id = parts.getOrNull(1) ?: return true
                val photo = incoming.remove(id) ?: return true
                val bytes = photo.chunks.toSortedMap().values.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                val safeName = photo.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "photo.jpg" }
                val outDir = File(context.getExternalFilesDir(null), "direct_photos").apply { mkdirs() }
                val outFile = File(outDir, "${System.currentTimeMillis()}-$safeName")
                outFile.writeBytes(bytes)
                onPhotoReceived(id, outFile.name)
                sendPacket("$PREFIX_ACK|$id|")
                true
            }
            PREFIX_ACK -> {
                parts.getOrNull(1)?.let(onPhotoAck)
                true
            }
            else -> false
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun decodeText(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private data class IncomingPhoto(
        val fileName: String,
        val expectedSize: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    private companion object {
        private const val CHUNK_SIZE = 12 * 1024
        private const val PREFIX_META = "PHO_META"
        private const val PREFIX_CHUNK = "PHO_CHUNK"
        private const val PREFIX_DONE = "PHO_DONE"
        private const val PREFIX_ACK = "PHO_ACK"
    }
}
