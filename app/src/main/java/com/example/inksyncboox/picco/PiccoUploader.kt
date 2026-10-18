package com.example.inksyncboox.picco

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sends files to a CrossPoint device over Wi-Fi using its documented web API
 * (CrossPoint docs/webserver-endpoints.md): GET /api/status, POST /mkdir, POST /upload.
 * Boox's firmware fork may differ, so every step fails with a clear message instead of retrying.
 */
class PiccoUploader(
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 60_000
) {

    class UploadException(message: String) : IOException(message)

    /** "192.168.1.20", "http://192.168.1.20/", "crosspoint.local:80" → "http://host[:port]". */
    fun normalizeHost(input: String): String {
        var h = input.trim().removeSuffix("/")
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://$h"
        return h
    }

    /** True if a CrossPoint device answers at [host]. */
    fun isReachable(host: String): Boolean = try {
        val conn = open("${normalizeHost(host)}/api/status", "GET")
        try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        false
    }

    /** Returns the first of [hosts] that answers, or null. */
    fun findDevice(hosts: List<String>): String? = hosts.filter { it.isNotBlank() }.distinct().firstOrNull { isReachable(it) }

    /** Creates [name] inside [parent]; errors are ignored (the folder may already exist). */
    fun mkdir(host: String, parent: String, name: String) {
        try {
            val conn = open("${normalizeHost(host)}/mkdir", "POST")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = "name=${enc(name)}&path=${enc(parent)}".toByteArray()
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
        }
    }

    /**
     * Uploads [file] into [remoteDir] as multipart field "file". [onProgress] gets (bytes sent,
     * total); [isCancelled] is checked between chunks.
     */
    fun upload(
        host: String,
        file: File,
        remoteDir: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ) {
        val boundary = "----InkSyncBoundary7MA4YWxkTrZu0gW"
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "'")}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val total = head.size + file.length() + tail.size

        val conn = open("${normalizeHost(host)}/upload?path=${enc(remoteDir)}", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(total)
            var sent = 0L
            conn.outputStream.use { out ->
                out.write(head)
                sent += head.size
                file.inputStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (isCancelled()) throw UploadException("Upload cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent, total)
                    }
                }
                out.write(tail)
                sent += tail.size
                onProgress(sent, total)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = (conn.errorStream ?: conn.inputStream)?.use { it.readBytes().decodeToString().take(200) }.orEmpty()
                throw UploadException("The device refused the upload (HTTP $code). $msg".trim())
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
