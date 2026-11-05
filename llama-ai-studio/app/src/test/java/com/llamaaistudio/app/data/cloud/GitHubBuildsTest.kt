package com.llamaaistudio.app.data.cloud

import com.llamaaistudio.app.ui.devstudio.ProjectBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GitHubBuildsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    @After fun tearDown() = server.shutdown()

    /** Minimal fake of the GitHub REST endpoints the builder uses. */
    private inner class FakeGitHub(val succeed: Boolean) : Dispatcher() {
        val trees = mutableListOf<JSONObject>()
        var repoExists = false
        var runPolls = 0
        var refPatched = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            val p = request.path!!
            val m = request.method
            fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
            if (request.getHeader("Authorization") != "Bearer tok") return ok("{}", 401)
            return when {
                p == "/user" -> ok("""{"login":"jr"}""")
                p == "/repos/jr/llama-studio-golf" -> if (repoExists) ok("""{"default_branch":"main"}""") else ok("{}", 404)
                p == "/user/repos" && m == "POST" -> { repoExists = true; ok("{}", 201) }
                p.endsWith("/git/ref/heads/main") -> ok("""{"object":{"sha":"base1"}}""")
                p.endsWith("/git/blobs") -> ok("""{"sha":"blob${request.sequenceNumber}"}""", 201)
                p.endsWith("/git/trees") -> { trees += JSONObject(request.body.readUtf8()); ok("""{"sha":"tree1"}""", 201) }
                p.endsWith("/git/commits") -> ok("""{"sha":"c1"}""", 201)
                p.endsWith("/git/refs/heads/main") && m == "PATCH" -> { refPatched = true; ok("{}") }
                p.contains("/actions/runs?head_sha=c1") -> {
                    runPolls++
                    val status = if (runPolls < 2) "in_progress" else "completed"
                    ok("""{"workflow_runs":[{"id":7,"status":"$status","conclusion":"${if (succeed) "success" else "failure"}","html_url":"https://github.com/jr/x/actions/runs/7"}]}""")
                }
                p.endsWith("/actions/runs/7/artifacts") ->
                    ok("""{"artifacts":[{"archive_download_url":"${server.url("/zip")}"}]}""")
                p == "/zip" -> MockResponse().setBody(Buffer().write(zipWithApk()))
                p.endsWith("/actions/runs/7/jobs") -> ok("""{"jobs":[{"id":9,"conclusion":"failure"}]}""")
                p.endsWith("/actions/jobs/9/logs") -> ok(
                    "2026-11-05T05:00:00.1234567Z > Task :app:compileDebugKotlin\n" +
                        "2026-11-05T05:00:01.0000000Z e: file:///home/runner/work/llama-studio-golf/llama-studio-golf/app/src/main/java/com/example/golf/MainActivity.kt:5:3 Unresolved reference 'foo'.\n"
                )
                else -> ok("""{"message":"unexpected $m $p"}""", 500)
            }
        }
    }

    private fun zipWithApk(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { it.putNextEntry(ZipEntry("app-debug.apk")); it.write("APKDATA".toByteArray()); it.closeEntry() }
        return bos.toByteArray()
    }

    private fun project(): File = tmp.newFolder("golf").also { root ->
        File(root, "app/src/main/java/com/example/golf").mkdirs()
        File(root, "app/src/main/java/com/example/golf/MainActivity.kt").writeText("package com.example.golf\n")
        File(root, "gradlew").writeText("#!/bin/sh\n")
        File(root, "local.properties").writeText("sdk.dir=/opt/android-sdk\n")
        File(root, "app/build/outputs").mkdirs(); File(root, "app/build/outputs/junk.txt").writeText("x")
    }

    private fun builds(fake: FakeGitHub): GitHubBuilds {
        server.dispatcher = fake
        server.start()
        return GitHubBuilds(OkHttpClient(), { "tok" }, server.url("").toString().trimEnd('/'), pollMillis = 1,
            base64 = { Base64.getEncoder().encodeToString(it) })
    }

    @Test
    fun pushesBuildsAndDownloadsApk() = runBlocking {
        val fake = FakeGitHub(succeed = true)
        val gh = builds(fake)
        val apk = File(tmp.root, "out/app-debug.apk")
        val steps = mutableListOf<String>()
        val r = gh.build(project(), "llama-studio-golf", apk) { steps += it }
        assertTrue(r.success)
        assertEquals("APKDATA", apk.readText())
        assertTrue(fake.refPatched)
        val paths = (0 until fake.trees[0].getJSONArray("tree").length()).map { fake.trees[0].getJSONArray("tree").getJSONObject(it) }
        val names = paths.map { it.getString("path") }
        assertTrue("workflow added", GitHubBuilds.WORKFLOW_PATH in names)
        assertFalse("no local.properties", "local.properties" in names)
        assertFalse("no build output", names.any { it.startsWith("app/build/") })
        assertEquals("100755", paths.first { it.getString("path") == "gradlew" }.getString("mode"))
        assertTrue(steps.any { it.startsWith("Creating private repo") })
    }

    @Test
    fun failedRunReturnsCompilerErrorsTheFixerCanUse() = runBlocking {
        val gh = builds(FakeGitHub(succeed = false))
        val r = gh.build(project(), "llama-studio-golf", File(tmp.root, "a.apk")) {}
        assertFalse(r.success)
        val errors = ProjectBuilder.parseErrors(r.log)
        assertEquals(listOf("line 5: Unresolved reference 'foo'."), errors["app/src/main/java/com/example/golf/MainActivity.kt"])
    }

    @Test
    fun badTokenGivesClearMessage() = runBlocking {
        server.dispatcher = FakeGitHub(true); server.start()
        val gh = GitHubBuilds(OkHttpClient(), { "wrong" }, server.url("").toString().trimEnd('/'), 1, { "" })
        val e = runCatching { gh.login() }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("rejected the token"))
    }

    @Test
    fun repoNamesAreSafe() = assertEquals("llama-studio-golf-sss", GitHubBuilds.repoName("Golf SSS"))

    @Test
    fun unreachableHostGivesClearMessage() = runBlocking {
        val dns = object : okhttp3.Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = throw java.net.UnknownHostException("no address") }
        var sleptOk = true
        val gh = GitHubBuilds(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(dns).build(), { "tok" }, "https://api.github.invalid", 1, { "" })
        val e = runCatching { gh.login() }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("Can't reach GitHub"))
        assertTrue(sleptOk)
    }
}
