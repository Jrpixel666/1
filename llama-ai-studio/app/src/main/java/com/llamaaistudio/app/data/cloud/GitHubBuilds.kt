package com.llamaaistudio.app.data.cloud

import android.util.Base64 as AndroidBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Compiles a Dev Studio project on GitHub Actions instead of the phone: pushes the sources to a
 * private repo in the user's account (one commit, replacing the tree), adds a workflow that runs
 * `assembleDebug`, waits for the run, then downloads the APK artifact — or the failed job's log so
 * the compiler errors can go to the auto-fixer.
 *
 * Needs a token with repo + workflow access (classic: `repo`, `workflow`; fine-grained:
 * Administration, Contents, Workflows read/write and Actions read).
 */
class GitHubBuilds(
    httpClient: OkHttpClient,
    private val token: () -> String?,
    private val api: String = "https://api.github.com",
    private val pollMillis: Long = 10_000,
    private val base64: (ByteArray) -> String = { AndroidBase64.encodeToString(it, AndroidBase64.NO_WRAP) },
) {
    data class Result(val success: Boolean, val log: List<String>, val apk: File?, val runUrl: String?)

    private val client = httpClient

    class GitHubException(message: String) : IOException(message)

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Pair<Int, String> {
        val t = token() ?: throw GitHubException("Add a GitHub token first")
        val url = if (path.startsWith("http")) path else api + path
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        // Phones drop DNS/connectivity for a moment (weak signal, switching Wi-Fi ↔ mobile);
        // retry those a few times before giving up.
        var attempt = 0
        while (true) {
            try {
                client.newCall(req).execute().use { r -> return r.code to (r.body?.string() ?: "") }
            } catch (e: java.net.UnknownHostException) {
                if (++attempt >= 4) throw GitHubException(
                    "Can't reach GitHub — the phone couldn't look up ${req.url.host}. Check you're online, and that " +
                        "Private DNS, a VPN or an ad-blocker isn't blocking it (try opening https://api.github.com in Chrome)."
                )
            } catch (e: java.net.SocketTimeoutException) {
                if (++attempt >= 4) throw GitHubException("GitHub didn't answer in time — check your connection and try again.")
            } catch (e: java.net.ConnectException) {
                if (++attempt >= 4) throw GitHubException("Couldn't connect to GitHub — check your connection and try again.")
            }
            Thread.sleep(2000L shl (attempt - 1)) // 2 s, 4 s, 8 s
        }
    }

    private fun json(path: String, method: String = "GET", body: JSONObject? = null, ok: Set<Int> = setOf(200, 201)): JSONObject {
        val (code, text) = request(path, method, body)
        if (code !in ok) throw GitHubException(explain(code, text, path))
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun explain(code: Int, text: String, path: String): String {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        return when {
            code == 401 -> "GitHub rejected the token — check it hasn't expired."
            code == 403 || code == 404 && path.contains("/git/") ->
                "GitHub refused ($code${if (msg.isNotEmpty()) ": $msg" else ""}). The token needs repo + workflow access."
            else -> "GitHub error $code on $path${if (msg.isNotEmpty()) ": $msg" else ""}"
        }
    }

    suspend fun login(): String = withContext(Dispatchers.IO) { json("/user").getString("login") }

    /**
     * Push [root] to `<login>/<repoName>`, wait for the Actions build and fetch the result.
     * [apkOut] receives the APK on success.
     */
    suspend fun build(root: File, repoName: String, apkOut: File, progress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val owner = login()
        val repo = "/repos/$owner/$repoName"
        val (code, text) = request(repo)
        val branch = if (code == 404) {
            progress("Creating private repo $owner/$repoName…")
            json("/user/repos", "POST", JSONObject().put("name", repoName).put("private", true).put("auto_init", true)
                .put("description", "Built by Llama AI Studio"))
            "main"
        } else if (code == 200) JSONObject(text).optString("default_branch", "main")
        else throw GitHubException(explain(code, text, repo))

        // A fresh auto_init repo needs a moment before its first commit is visible.
        var head: String? = null
        for (i in 0 until 15) {
            val (c, t) = request("$repo/git/ref/heads/$branch")
            if (c == 200) { head = JSONObject(t).getJSONObject("object").getString("sha"); break }
            delay(minOf(pollMillis, 2000))
        }
        head ?: throw GitHubException("The new repo isn't ready yet — try again in a minute.")

        val files = collectFiles(root)
        progress("Uploading ${files.size} files…")
        val tree = JSONArray()
        for ((path, file) in files) {
            val blob = json("$repo/git/blobs", "POST", JSONObject().put("content", base64(file.readBytes())).put("encoding", "base64"))
            tree.put(JSONObject().put("path", path).put("mode", if (path == "gradlew") "100755" else "100644")
                .put("type", "blob").put("sha", blob.getString("sha")))
        }
        val wf = json("$repo/git/blobs", "POST", JSONObject().put("content", WORKFLOW).put("encoding", "utf-8"))
        tree.put(JSONObject().put("path", WORKFLOW_PATH).put("mode", "100644").put("type", "blob").put("sha", wf.getString("sha")))
        val treeSha = json("$repo/git/trees", "POST", JSONObject().put("tree", tree)).getString("sha")
        val commit = json("$repo/git/commits", "POST", JSONObject().put("message", "Build from Llama AI Studio")
            .put("tree", treeSha).put("parents", JSONArray().put(head))).getString("sha")
        json("$repo/git/refs/heads/$branch", "PATCH", JSONObject().put("sha", commit).put("force", true))

        progress("Waiting for GitHub Actions…")
        var run: JSONObject? = null
        val started = System.currentTimeMillis()
        while (true) {
            val runs = json("$repo/actions/runs?head_sha=$commit&per_page=5").optJSONArray("workflow_runs")
            run = runs?.optJSONObject(0)
            val status = run?.optString("status")
            if (status == "completed") break
            if (run == null && System.currentTimeMillis() - started > 5 * 60_000) {
                throw GitHubException("No Actions run started — check Actions is enabled on $owner/$repoName.")
            }
            if (System.currentTimeMillis() - started > 45 * 60_000) throw GitHubException("The GitHub build took over 45 minutes.")
            progress(if (run == null) "Waiting for GitHub Actions to start…" else "GitHub is building ($status)…")
            delay(pollMillis)
        }
        val r = run!!
        val runId = r.getLong("id")
        val runUrl = r.optString("html_url").ifBlank { null }
        if (r.optString("conclusion") != "success") {
            progress("GitHub build failed — reading the log…")
            return@withContext Result(false, failedLog("$repo/actions/runs/$runId/jobs"), null, runUrl)
        }
        progress("Downloading the APK…")
        val arts = json("$repo/actions/runs/$runId/artifacts").optJSONArray("artifacts")
        val zipUrl = arts?.optJSONObject(0)?.optString("archive_download_url")
            ?: throw GitHubException("The build passed but uploaded no APK.")
        val t = token()!!
        client.newCall(Request.Builder().url(zipUrl).header("Authorization", "Bearer $t").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw GitHubException("APK download failed (${resp.code})")
            if (!extractApk(resp.body!!.byteStream(), apkOut)) throw GitHubException("No .apk inside the artifact")
        }
        Result(true, listOf("BUILD SUCCESSFUL on GitHub"), apkOut, runUrl)
    }

    private fun failedLog(jobsPath: String): List<String> {
        val jobs = json(jobsPath).optJSONArray("jobs") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until jobs.length()) {
            val j = jobs.getJSONObject(i)
            if (j.optString("conclusion") != "failure") continue
            val (code, text) = request("${jobsPath.substringBefore("/actions/")}/actions/jobs/${j.getLong("id")}/logs")
            if (code == 200) out += cleanLog(text)
        }
        return out
    }

    companion object {
        const val WORKFLOW_PATH = ".github/workflows/llama-studio-build.yml"

        // The project's gradle.properties points aapt2 at the phone's aarch64 build; GitHub's
        // runners are x86-64 and have their own SDK, so that line and local.properties are dropped.
        val WORKFLOW = """
            name: Build APK
            on:
              push:
              workflow_dispatch:
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: '17'
                  - uses: gradle/actions/setup-gradle@v4
                  - name: Build
                    run: |
                      sed -i '/aapt2FromMavenOverride/d' gradle.properties || true
                      rm -f local.properties
                      chmod +x gradlew
                      ./gradlew assembleDebug --no-daemon --console=plain
                  - uses: actions/upload-artifact@v4
                    with:
                      name: apk
                      path: app/build/outputs/apk/debug/*.apk
        """.trimIndent() + "\n"

        private val SKIP_DIRS = setOf("build", ".gradle", ".kotlin", ".idea", ".cxx", ".git")

        /** Project files to push (relative path → file). */
        fun collectFiles(root: File): List<Pair<String, File>> = root.walkTopDown()
            .onEnter { it == root || it.name !in SKIP_DIRS }
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') to it }
            .filter { (p, _) -> p != "local.properties" && p != WORKFLOW_PATH }
            .sortedBy { it.first }
            .toList()

        private val TIMESTAMP = Regex("""^\d{4}-\d\d-\d\dT[\d:.]+Z ?""")

        /** Actions logs prefix every line with a timestamp; strip it so `e: file:line` parses. */
        fun cleanLog(text: String): List<String> = text.lineSequence().map { it.replace(TIMESTAMP, "") }.toList()

        fun extractApk(input: java.io.InputStream, out: File): Boolean {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: return false
                    if (!e.isDirectory && e.name.endsWith(".apk")) {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        return true
                    }
                }
            }
        }

        /** Repo name for a project: `llama-studio-<name>`. */
        fun repoName(project: String) = "llama-studio-" + project.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { "app" }
    }
}
