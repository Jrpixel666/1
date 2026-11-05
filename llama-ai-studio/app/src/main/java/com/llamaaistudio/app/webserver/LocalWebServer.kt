package com.llamaaistudio.app.webserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A small static-file HTTP/1.1 server built into the app, so Web Studio sites can be tested
 * with one tap — no Termux, no installs. Serves one project folder at a time.
 *
 * - GET/HEAD only, `index.html` for directories, MIME types, `Cache-Control: no-store`.
 * - Every path is canonicalised and must stay inside the served folder.
 * - Live reload: HTML pages get a tiny script that polls `/__llama/version` (a fingerprint of
 *   the folder's file timestamps) and reloads the page when anything changes.
 * - Binds to 127.0.0.1 by default; [Config.shareOnLan] binds all interfaces so other devices
 *   on the same Wi-Fi can open the site too.
 */
class LocalWebServer {

    data class Config(
        val root: File,
        val port: Int = DEFAULT_PORT,
        val shareOnLan: Boolean = false,
        val label: String = root.name,
        val projectId: Long? = null,
        val liveReload: Boolean = true,
    )

    data class State(
        val running: Boolean = false,
        val config: Config? = null,
        val port: Int = 0,
        val lanAddress: String? = null,
        val requestCount: Long = 0,
        val error: String? = null,
    ) {
        val localUrl: String? get() = if (running) "http://127.0.0.1:$port/" else null
        val lanUrl: String? get() = if (running && lanAddress != null) "http://$lanAddress:$port/" else null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null
    private val requests = AtomicLong(0)

    /**
     * Starts serving [config]. If the port is taken it tries the next few. Restarting with a
     * new config (other project, LAN toggle) replaces the running server.
     */
    fun start(config: Config): Result<State> = synchronized(lock) {
        stopLocked()
        if (!config.root.isDirectory) {
            return fail(config, "Folder not found: ${config.root}")
        }
        val bindHost = if (config.shareOnLan) "0.0.0.0" else "127.0.0.1"
        val candidates = if (config.port == 0) listOf(0) else (config.port until config.port + PORT_ATTEMPTS).toList()
        var socket: ServerSocket? = null
        var lastError: Exception? = null
        for (p in candidates) {
            try {
                socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(bindHost), p), BACKLOG)
                }
                break
            } catch (e: BindException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (socket == null) return fail(config, "Couldn't open a port: ${lastError?.message}")

        val pool = ThreadPoolExecutor(
            0, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "web-server-worker").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
        serverSocket = socket
        workers = pool
        requests.set(0)
        val canonicalRoot = config.root.canonicalFile
        val thread = Thread({ acceptLoop(socket, pool, canonicalRoot, config) }, "web-server-accept").apply {
            isDaemon = true
        }
        acceptThread = thread
        thread.start()

        val newState = State(
            running = true,
            config = config,
            port = socket.localPort,
            lanAddress = if (config.shareOnLan) lanIpv4Address() else null,
        )
        _state.value = newState
        Result.success(newState)
    }

    fun stop() = synchronized(lock) {
        stopLocked()
        _state.value = State()
    }

    private fun stopLocked() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
    }

    private fun fail(config: Config, message: String): Result<State> {
        _state.value = State(running = false, config = config, error = message)
        return Result.failure(IOException(message))
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService, root: File, config: Config) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: IOException) {
                continue
            }
            try {
                pool.execute { handle(client, root, config) }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket, root: File, config: Config) {
        client.use { sock ->
            try {
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()
                val requestLine = readHead(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return send(output, 400, "text/plain", "Bad request".toByteArray(), false)
                val method = parts[0].uppercase(Locale.ROOT)
                val head = method == "HEAD"
                if (method != "GET" && !head) {
                    return send(output, 405, "text/plain", "Method not allowed".toByteArray(), false, "Allow: GET, HEAD")
                }
                val count = requests.incrementAndGet()
                if (count % 5 == 0L) _state.value = _state.value.copy(requestCount = count)
                serve(output, parts[1], head, root, config)
            } catch (_: IOException) {
                // client went away / timed out — nothing to do
            }
        }
    }

    private fun serve(out: OutputStream, rawTarget: String, head: Boolean, root: File, config: Config) {
        val path = try {
            URI(rawTarget.substringBefore('?').substringBefore('#')).path ?: "/"
        } catch (e: Exception) {
            return send(out, 400, "text/plain", "Bad request".toByteArray(), head)
        }

        if (path == VERSION_PATH) {
            return send(out, 200, "text/plain", fingerprint(root).toByteArray(), head)
        }

        val relative = path.trimStart('/')
        val target = File(root, relative).canonicalFile
        val inside = target == root || target.path.startsWith(root.path + File.separator)
        if (!inside || relative.split('/').any { it == ".." }) {
            return send(out, 403, "text/html", errorPage(403, "Forbidden"), head)
        }

        if (target.isDirectory) {
            if (!path.endsWith("/")) {
                return send(out, 301, "text/plain", ByteArray(0), head, "Location: $path/")
            }
            val index = File(target, "index.html")
            return if (index.isFile) serveFile(out, index, head, config) else
                send(out, 404, "text/html", errorPage(404, "No index.html in ${path}"), head)
        }
        if (!target.isFile) return send(out, 404, "text/html", errorPage(404, "Not found: $path"), head)
        serveFile(out, target, head, config)
    }

    private fun serveFile(out: OutputStream, file: File, head: Boolean, config: Config) {
        val type = mimeType(file.name)
        if (type.startsWith("text/html") && config.liveReload) {
            val html = injectLiveReload(file.readText())
            return send(out, 200, type, html.toByteArray(), head)
        }
        val length = file.length()
        writeHeaders(out, 200, type, length)
        if (!head) file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun send(
        out: OutputStream,
        status: Int,
        type: String,
        body: ByteArray,
        head: Boolean,
        vararg extraHeaders: String,
    ) {
        writeHeaders(out, status, if (type.startsWith("text/") && !type.contains("charset")) "$type; charset=utf-8" else type,
            body.size.toLong(), *extraHeaders)
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHeaders(out: OutputStream, status: Int, type: String, length: Long, vararg extra: String) {
        val sb = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Connection: close\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** Reads the request line and skips the headers; returns null on EOF/oversized requests. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var requestLine: String? = null
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > MAX_HEADER_BYTES) return null
            if (b == '\n'.code) {
                val line = buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                buf.reset()
                if (requestLine == null) {
                    if (line.isEmpty()) continue
                    requestLine = line
                } else if (line.isEmpty()) {
                    return requestLine
                }
            } else {
                buf.write(b)
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val VERSION_PATH = "/__llama/version"
        private const val PORT_ATTEMPTS = 10
        private const val BACKLOG = 32
        private const val MAX_WORKERS = 8
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_FINGERPRINT_FILES = 4000

        private val LIVE_RELOAD_SCRIPT = """
            <script>/* Llama AI Studio live reload */(function(){var v=null;function p(){fetch('$VERSION_PATH',{cache:'no-store'}).then(function(r){return r.text()}).then(function(t){if(v!==null&&t!==v){location.reload();return}v=t;setTimeout(p,1000)}).catch(function(){setTimeout(p,2000)})}p()})();</script>
        """.trimIndent()

        fun injectLiveReload(html: String): String {
            val idx = html.lastIndexOf("</body>", ignoreCase = true)
            return if (idx >= 0) html.substring(0, idx) + LIVE_RELOAD_SCRIPT + "\n" + html.substring(idx)
            else html + "\n" + LIVE_RELOAD_SCRIPT + "\n"
        }

        /** Changes whenever a file under [root] is added, removed or modified. */
        fun fingerprint(root: File): String {
            var count = 0
            var hash = 17L
            root.walkTopDown()
                .onEnter { it == root || (it.name != "node_modules" && !it.name.startsWith(".")) }
                .filter { it.isFile }
                .take(MAX_FINGERPRINT_FILES)
                .forEach { f ->
                    count++
                    hash = hash * 31 + f.path.hashCode()
                    hash = hash * 31 + f.lastModified()
                    hash = hash * 31 + f.length()
                }
            return "$count-${java.lang.Long.toHexString(hash)}"
        }

        fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "txt", "md" -> "text/plain; charset=utf-8"
            "xml" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wasm" -> "application/wasm"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"; 301 -> "Moved Permanently"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
        }

        private fun errorPage(status: Int, message: String): ByteArray =
            ("<!doctype html><meta charset=utf-8><title>$status</title>" +
                "<body style=\"font-family:system-ui;padding:2rem\"><h1>$status</h1><p>" +
                message.replace("<", "&lt;") + "</p></body>").toByteArray()

        /** First site-local IPv4 address (the phone's Wi-Fi address), if any. */
        fun lanIpv4Address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
