package com.example.inksyncboox.picco

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** Runs the uploader against a tiny fake CrossPoint web server. */
class PiccoUploaderTest {

    private data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)

    private lateinit var server: ServerSocket
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private var uploadStatus = 200

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) out.write(c)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    @Before
    fun start() {
        server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    val input = s.getInputStream()
                    val line = readLine(input)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val h = readLine(input)
                        if (h.isEmpty()) break
                        headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    requests += Request(line, headers, body)
                    val status = if (line.startsWith("POST /upload")) uploadStatus else 200
                    val msg = "OK".toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Length: ${msg.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(msg)
                        flush()
                    }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private val host get() = "127.0.0.1:${server.localPort}"

    @Test
    fun `finds the device and uploads a file as multipart`() {
        val file = File.createTempFile("book", ".xtch").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
        val up = PiccoUploader(connectTimeoutMs = 2000, readTimeoutMs = 5000)
        assertEquals(host, up.findDevice(listOf("", host)))
        up.mkdir(host, "/", "Comics")
        var lastProgress = 0L
        up.upload(host, file, "/Comics", onProgress = { sent, _ -> lastProgress = sent })

        val upload = requests.first { it.line.startsWith("POST /upload") }
        assertTrue(upload.line.startsWith("POST /upload?path=%2FComics "))
        assertTrue(upload.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        val text = String(upload.body, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"file\"; filename=\"${file.name}\""))
        val start = text.indexOf("\r\n\r\n") + 4
        assertTrue(upload.body.copyOfRange(start, start + 100_000).contentEquals(file.readBytes()))
        assertEquals(upload.body.size.toLong(), lastProgress)

        val mkdir = requests.first { it.line.startsWith("POST /mkdir") }
        assertEquals("name=Comics&path=%2F", String(mkdir.body))
        file.delete()
    }

    @Test
    fun `refused uploads report the status`() {
        uploadStatus = 500
        val file = File.createTempFile("book", ".xtc").apply { writeBytes(ByteArray(10)) }
        val result = runCatching { PiccoUploader(2000, 5000).upload(host, file, "/Comics") }
        assertTrue(result.exceptionOrNull()?.message!!.contains("HTTP 500"))
        file.delete()
    }

    @Test
    fun `unreachable hosts are skipped`() {
        val up = PiccoUploader(connectTimeoutMs = 500, readTimeoutMs = 500)
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(up.isReachable("127.0.0.1:$closed"))
        assertNull(up.findDevice(listOf("127.0.0.1:$closed")))
        assertEquals("http://10.0.0.5", up.normalizeHost(" 10.0.0.5/ "))
    }
}
