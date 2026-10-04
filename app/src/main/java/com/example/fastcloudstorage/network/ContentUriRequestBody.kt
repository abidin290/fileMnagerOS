package com.example.fastcloudstorage.network

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

class ContentUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType?,
    private val knownLength: Long,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = knownLength

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var sent = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent, knownLength)
            }
        } ?: error("Tidak bisa membaca file yang dipilih")
    }
}
