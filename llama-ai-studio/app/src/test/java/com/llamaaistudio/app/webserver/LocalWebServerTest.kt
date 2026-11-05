package com.llamaaistudio.app.webserver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket

class LocalWebServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = LocalWebServer()
    private lateinit var site: File
    private var port = 0

    @Before
    fun setUp() {
        val parent = tmp.newFolder("projects")
        site = File(parent, "site").apply { mkdirs() }
        File(site, "index.html").writeText("<html><body><h1>Hello</h1></body></html>")
        File(site, "style.css").writeText("h1{color:red}")
        File(site, "docs").mkdirs()
        File(site, "docs/index.html").writeText("<p>docs</p>")
        File(parent, "secret.txt").writeText("top secret")
        port = server.start(LocalWebServer.Config(root = site, port = 0)).getOrThrow().port
    }

    @After
    fun tearDown() = server.stop()

    private data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    /** Raw HTTP so paths like /../ are sent exactly as written. */
    private fun request(path: String, method: String = "GET"): Response {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("$method $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val raw = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val head = raw.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
            return Response(lines[0].split(' ')[1].toInt(), headers, raw.substringAfter("\r\n\r\n"))
        }
    }

    @Test
    fun servesIndexWithLiveReloadInjected() {
        val r = request("/")
        assertEquals(200, r.status)
        assertTrue(r.headers["content-type"]!!.startsWith("text/html"))
        assertTrue(r.body.contains("<h1>Hello</h1>"))
        assertTrue("live reload script before </body>", r.body.indexOf("__llama/version") in 0 until r.body.indexOf("</body>"))
        assertEquals(r.body.toByteArray().size.toString(), r.headers["content-length"])
        assertEquals("no-store", r.headers["cache-control"])
    }

    @Test
    fun servesAssetsWithMimeTypesAndQueryStrings() {
        val r = request("/style.css?v=2")
        assertEquals(200, r.status)
        assertEquals("text/css; charset=utf-8", r.headers["content-type"])
        assertEquals("h1{color:red}", r.body)
    }

    @Test
    fun headHasNoBody() {
        val r = request("/style.css", "HEAD")
        assertEquals(200, r.status)
        assertEquals("13", r.headers["content-length"])
        assertEquals("", r.body)
    }

    @Test
    fun directoriesRedirectThenServeIndex() {
        val redirect = request("/docs")
        assertEquals(301, redirect.status)
        assertEquals("/docs/", redirect.headers["location"])
        assertTrue(request("/docs/").body.contains("<p>docs</p>"))
    }

    @Test
    fun blocksPathTraversal() {
        for (path in listOf("/../secret.txt", "/%2e%2e/secret.txt", "/docs/../../secret.txt")) {
            val r = request(path)
            assertTrue("$path -> ${r.status}", r.status == 403 || r.status == 404)
            assertFalse(r.body.contains("top secret"))
        }
    }

    @Test
    fun notFoundAndMethodNotAllowed() {
        assertEquals(404, request("/nope.html").status)
        assertEquals(405, request("/", "POST").status)
    }

    @Test
    fun versionFingerprintChangesWhenFilesChange() {
        val before = request(LocalWebServer.VERSION_PATH).body
        assertEquals(before, request(LocalWebServer.VERSION_PATH).body)
        File(site, "new.js").writeText("console.log(1)")
        assertNotEquals(before, request(LocalWebServer.VERSION_PATH).body)
    }

    @Test
    fun stopReleasesThePort() {
        server.stop()
        assertFalse(server.state.value.running)
        // Binding the same port again proves the server let go of it. (Probing with a connect
        // is racy: the OS may hand a just-freed ephemeral port to another process.)
        java.net.ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress("127.0.0.1", port))
            assertTrue(s.isBound)
        }
    }

    @Test
    fun fallsBackToNextPortWhenBusy() {
        val other = LocalWebServer()
        val blocker = java.net.ServerSocket(0)
        try {
            val state = other.start(LocalWebServer.Config(root = site, port = blocker.localPort)).getOrThrow()
            assertNotEquals(blocker.localPort, state.port)
        } finally {
            other.stop()
            blocker.close()
        }
    }
}
