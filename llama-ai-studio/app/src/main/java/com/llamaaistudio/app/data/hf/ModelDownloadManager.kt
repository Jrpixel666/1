package com.llamaaistudio.app.data.hf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED }

data class DownloadState(
    val file: GgufFileOption,
    val destFile: File,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
) {
    val totalBytes: Long get() = file.sizeBytes
    val progressFraction: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Resumable GGUF downloader: streams into `<name>.gguf.part`, supports HTTP Range-based
 * pause/resume, verifies SHA-256 against Hugging Face's published LFS hash when available,
 * then hands the finished file to [onComplete]. Generic over what kind of GGUF it is (LLM
 * weights, a diffusion checkpoint, ...) — that's purely the caller's business via
 * [onComplete] and [destinationDir].
 */
class ModelDownloadManager(
    private val destinationDir: File,
    private val onComplete: suspend (file: File, meta: GgufFileOption, sha256: String?, verified: Boolean) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    // Process-wide scope: downloads must survive the hosting screen's ViewModel being
    // cleared (e.g. the user switches tabs mid-download).
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }

    private fun keyFor(file: GgufFileOption) = "${file.modelId}/${file.filename}"

    fun start(file: GgufFileOption) {
        val key = keyFor(file)
        if (jobs[key]?.isActive == true) return

        val destFile = File(destinationDir, sanitizeFilename(file.filename))
        val partFile = File(destFile.parentFile, destFile.name + ".part")

        _downloads.update { it + (key to DownloadState(file, destFile, partFile.length(), DownloadStatus.QUEUED)) }

        jobs[key] = managerScope.launch {
            runDownload(key, file, destFile, partFile)
        }
    }

    fun pause(file: GgufFileOption) {
        val key = keyFor(file)
        jobs[key]?.cancel()
        jobs.remove(key)
        _downloads.update { map ->
            map[key]?.let { map + (key to it.copy(status = DownloadStatus.PAUSED)) } ?: map
        }
    }

    fun resume(file: GgufFileOption) = start(file)

    fun cancel(file: GgufFileOption) {
        val key = keyFor(file)
        jobs[key]?.cancel()
        jobs.remove(key)
        _downloads.value[key]?.let { state ->
            File(state.destFile.parentFile, state.destFile.name + ".part").delete()
        }
        _downloads.update { it - key }
    }

    private suspend fun runDownload(key: String, file: GgufFileOption, destFile: File, partFile: File) {
        try {
            update(key) { it.copy(status = DownloadStatus.DOWNLOADING) }
            var startByte = if (partFile.exists()) partFile.length() else 0L

            val alreadyComplete = startByte > 0 && file.sizeBytes > 0 && startByte >= file.sizeBytes
            if (!alreadyComplete) {
                val requestBuilder = Request.Builder().url(file.downloadUrl)
                if (startByte > 0) requestBuilder.addHeader("Range", "bytes=$startByte-")

                // use{}: the response (and its pooled connection) was never closed before,
                // leaking a socket on every failed or paused download.
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 416 && startByte > 0) {
                        // Range not satisfiable: the .part file already holds the whole thing
                        // (e.g. the app died between finishing and renaming). Verify it below.
                        return@use
                    }
                    if (!response.isSuccessful) {
                        update(key) { it.copy(status = DownloadStatus.FAILED, error = "HTTP ${response.code}") }
                        return
                    }

                    val resumed = response.code == 206
                    if (!resumed) startByte = 0L // server ignored Range; restart from scratch

                    val body = response.body ?: run {
                        update(key) { it.copy(status = DownloadStatus.FAILED, error = "Empty response body") }
                        return
                    }

                    RandomAccessFile(partFile, "rw").use { raf ->
                        if (!resumed) raf.setLength(0)
                        raf.seek(startByte)
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            var total = startByte
                            var lastReport = 0L
                            while (currentCoroutineContext().isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                total += n
                                // Throttled: one state emission per 64KB chunk meant tens of
                                // thousands of recompositions per GB downloaded.
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                    lastReport = now
                                    val snapshot = total
                                    update(key) { it.copy(downloadedBytes = snapshot) }
                                }
                            }
                            val finalTotal = total
                            update(key) { it.copy(downloadedBytes = finalTotal) }
                        }
                    }
                }
            }

            if (!currentCoroutineContext().isActive) return // cancelled -> partial file kept for resume

            if (file.sizeBytes > 0 && partFile.length() < file.sizeBytes) {
                update(key) {
                    it.copy(status = DownloadStatus.FAILED, error = "Connection closed early — tap resume to continue")
                }
                return
            }

            update(key) { it.copy(status = DownloadStatus.VERIFYING) }

            val expectedSha = file.sha256
            var verified = false
            if (expectedSha != null) {
                val actual = sha256Of(partFile)
                verified = actual.equals(expectedSha, ignoreCase = true)
                if (!verified) {
                    update(key) { it.copy(status = DownloadStatus.FAILED, error = "SHA-256 mismatch — file may be corrupt") }
                    partFile.delete()
                    return
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                update(key) { it.copy(status = DownloadStatus.FAILED, error = "Could not move the finished download into place") }
                return
            }
            onComplete(destFile, file, expectedSha, verified)
            update(key) { it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = destFile.length()) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            update(key) { it.copy(status = DownloadStatus.FAILED, error = e.message ?: "Download failed") }
        }
    }

    private fun update(key: String, transform: (DownloadState) -> DownloadState) {
        _downloads.update { map -> map[key]?.let { map + (key to transform(it)) } ?: map }
    }

    private suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeFilename(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
