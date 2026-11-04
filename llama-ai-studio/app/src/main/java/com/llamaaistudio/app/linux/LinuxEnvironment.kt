package com.llamaaistudio.app.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The app's built-in Linux: an Alpine Linux root filesystem run through proot (shipped in the
 * APK as libproot.so). Gives the app a real package manager (`apk`) for Node.js, OpenJDK,
 * Gradle, git, etc. — no Termux, no root.
 */
class LinuxEnvironment(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    sealed interface State {
        data object NotInstalled : State
        data class Installing(val step: String, val progress: Float? = null) : State
        data class Ready(val alpineVersion: String) : State
        data class Failed(val message: String) : State
    }

    data class RunResult(val exitCode: Int, val output: String)

    private val base = File(context.filesDir, "linux")
    val rootfs = File(base, "alpine")
    private val prootTmp = File(base, "tmp")
    private val marker = File(base, "installed.txt")

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val prootBinary = File(nativeDir, "libproot.so")
    val loaderBinary = File(nativeDir, "libproot-loader.so")

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state

    private val downloadClient = httpClient.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()

    private fun initialState(): State =
        if (marker.isFile && File(rootfs, "bin/sh").let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
            State.Ready(marker.readText().trim().ifEmpty { ALPINE_VERSION })
        } else {
            State.NotInstalled
        }

    val isReady: Boolean get() = _state.value is State.Ready

    /** Host directories made visible inside Linux (Web/Dev Studio projects). */
    fun defaultBinds(): List<ProotCommand.Bind> = listOf(
        ProotCommand.Bind(File(context.filesDir, "web_projects").apply { mkdirs() }.absolutePath, "/projects/web"),
        ProotCommand.Bind(File(context.filesDir, "projects").apply { mkdirs() }.absolutePath, "/projects/apps"),
    )

    /** Downloads, verifies and unpacks Alpine; safe to call again after a failure. */
    suspend fun install() = withContext(Dispatchers.IO) {
        try {
            checkBinaries()
            base.mkdirs()
            prootTmp.mkdirs()
            val archive = File(base, "alpine-minirootfs.tar.gz")
            download(archive)

            _state.value = State.Installing("Checking download…")
            val actual = sha256(archive)
            if (!actual.equals(ALPINE_SHA256, ignoreCase = true)) {
                archive.delete()
                throw IOException("Checksum mismatch for the Alpine download (got $actual) — try again")
            }

            _state.value = State.Installing("Unpacking Alpine Linux…")
            if (rootfs.exists()) rootfs.deleteRecursively()
            val result = archive.inputStream().use { input ->
                RootfsExtractor.extractTarGz(input, rootfs) { n ->
                    _state.value = State.Installing("Unpacking Alpine Linux… ($n files)")
                }
            }
            archive.delete()
            configureRootfs()
            marker.writeText(ALPINE_VERSION)
            _state.value = State.Ready(ALPINE_VERSION)
            "Unpacked ${result.files} files, ${result.symlinks} symlinks"
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.toString())
            throw e
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        marker.delete()
        rootfs.deleteRecursively()
        prootTmp.deleteRecursively()
        _state.value = State.NotInstalled
    }

    private fun checkBinaries() {
        if (!prootBinary.isFile || !loaderBinary.isFile) {
            throw IOException(
                "proot isn't in this APK's native libraries ($nativeDir). Rebuild with the buildProot task."
            )
        }
    }

    private suspend fun download(dest: File) {
        _state.value = State.Installing("Downloading Alpine Linux $ALPINE_VERSION…", 0f)
        val request = Request.Builder().url(ALPINE_URL).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var lastReport = 0L
                    while (currentCoroutineContext().isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            lastReport = now
                            _state.value = State.Installing(
                                "Downloading Alpine Linux $ALPINE_VERSION… ${read / 1024} KB",
                                total?.let { read.toFloat() / it },
                            )
                        }
                    }
                }
            }
        }
    }

    /** DNS, hosts and package repositories so `apk` works out of the box. */
    private fun configureRootfs() {
        File(rootfs, "etc").mkdirs()
        // Android has no /etc/resolv.conf for us to bind, so give the guest public resolvers.
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        File(rootfs, "etc/apk/repositories").apply { parentFile?.mkdirs() }.writeText(
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/community\n"
        )
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/profile.d/llama-studio.sh").writeText(
            "# Llama AI Studio: projects are mounted under /projects\n" +
                "export PS1='alpine:\\w# '\n"
        )
        listOf("tmp", "root", "projects/web", "projects/apps").forEach { File(rootfs, it).mkdirs() }
    }

    /**
     * Runs [command] with `/bin/sh -lc` inside Alpine, streaming merged stdout/stderr to
     * [onOutput]. Cancelling the calling coroutine kills the command (and its children).
     */
    suspend fun run(
        command: String,
        workdir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit = {},
    ): RunResult = withContext(Dispatchers.IO) {
        checkBinaries()
        if (!isReady) throw IOException("Linux isn't installed yet")
        prootTmp.mkdirs()
        val pb = ProcessBuilder(ProotCommand.argv(prootBinary, rootfs, command, defaultBinds(), workdir, extraEnv))
            .redirectErrorStream(true)
            .directory(base)
        pb.environment().apply {
            clear()
            putAll(ProotCommand.hostEnv(loaderBinary, prootTmp))
            put("PATH", "/system/bin")
            put("HOME", base.absolutePath)
        }
        val process = pb.start()
        val output = StringBuilder()
        coroutineScope {
            // The read below blocks; Stop (cancellation) has to kill the process to unblock it.
            // proot's --kill-on-exit then takes every guest process down with it.
            val killer = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            try {
                process.outputStream.close() // no interactive stdin in this terminal
                val reader = process.inputStream.bufferedReader()
                val buf = CharArray(4096)
                while (true) {
                    val n = try {
                        reader.read(buf)
                    } catch (e: IOException) {
                        -1 // stream closed because the process was killed
                    }
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    if (output.length < MAX_CAPTURE) output.append(chunk)
                    onOutput(chunk)
                }
                ensureActive()
                RunResult(process.waitFor(), output.toString())
            } finally {
                killer.cancel()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    /**
     * Checks, step by step, whether this phone lets the app run Linux programs — the key
     * unknown on Android 10+ (W^X rules). Returns a human-readable report.
     */
    suspend fun selfTest(onOutput: (String) -> Unit): Boolean {
        fun say(s: String) = onOutput(s + "\n")
        say("== Self-test ==")
        say("proot binary: ${prootBinary.path} (${if (prootBinary.canExecute()) "executable" else "NOT executable"})")
        say("loader:       ${loaderBinary.path} (${if (loaderBinary.isFile) "present" else "MISSING"})")
        if (!isReady) {
            say("Linux isn't installed yet — tap Install first.")
            return false
        }
        val steps = listOf(
            "Run a Linux program (busybox)" to "echo exec-ok",
            "Alpine version" to "cat /etc/alpine-release",
            "Kernel / CPU" to "uname -srm",
            "Write files" to "echo ok > /tmp/.write-test && cat /tmp/.write-test && rm /tmp/.write-test",
            "Network (DNS + HTTPS)" to "wget -q -T 10 -O /dev/null https://dl-cdn.alpinelinux.org/alpine/ && echo net-ok",
        )
        var allOk = true
        for ((label, cmd) in steps) {
            say("\n-- $label: $cmd")
            val result = runCatching { run(cmd, onOutput = onOutput) }
            val ok = result.getOrNull()?.exitCode == 0
            if (!ok) {
                allOk = false
                val out = result.getOrNull()?.output ?: result.exceptionOrNull()?.message.orEmpty()
                say("   ✗ failed (exit ${result.getOrNull()?.exitCode ?: "error"})")
                if (label.startsWith("Run a Linux program")) {
                    say(diagnoseExecFailure(out))
                    break
                }
            } else {
                say("   ✓ ok")
            }
        }
        say(if (allOk) "\nAll checks passed — this phone can run the built-in Linux." else "\nSome checks failed (see above).")
        return allOk
    }

    private fun diagnoseExecFailure(output: String): String = when {
        output.contains("Permission denied", ignoreCase = true) || output.contains("EACCES") ->
            "   → Android refused to execute the Linux program (W^X restriction on app storage). " +
                "This phone needs the fallback build (lower targetSdk). Please report this output."
        output.contains("ptrace", ignoreCase = true) ->
            "   → The kernel blocked ptrace, which proot needs. Please report this output."
        else -> "   → Unexpected failure. Please report the output above."
    }

    companion object {
        const val ALPINE_VERSION = "3.24.3"
        const val ALPINE_BRANCH = "v3.24"
        const val ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/aarch64/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"
        /** From Alpine's published .sha256 for exactly this file. */
        const val ALPINE_SHA256 = "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50"
        private const val MAX_CAPTURE = 256 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
