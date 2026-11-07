package com.llamaaistudio.app.data.repo

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import com.llamaaistudio.app.data.db.LocalModelDao
import com.llamaaistudio.app.data.db.LocalModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ModelRepository(
    private val context: Context,
    private val dao: LocalModelDao,
) {
    val storage = ModelStorage(context)

    /** Where new downloads/imports go — follows the storage setting. */
    val modelsDir: File get() = storage.currentDir()

    /**
     * Registers model files already in [dir] that the app doesn't know about (e.g. models kept on
     * /sdcard from before a reinstall). Returns how many were added.
     */
    suspend fun scanFolder(dir: File = modelsDir): Int = withContext(Dispatchers.IO) {
        val known = dao.getAll().map { it.filePath }.toSet()
        val found = dir.listFiles { f -> f.isFile && (f.name.endsWith(".gguf") || f.name.endsWith(".litertlm")) }.orEmpty()
            .filter { it.absolutePath !in known }
        found.forEach { registerModel(it) }
        found.size
    }

    /**
     * Moves every installed model into the current storage folder, keeping its details
     * (name, hashes, last used). [progress] gets "name (i/n)". Returns moved count.
     */
    suspend fun moveAllTo(dir: File = modelsDir, progress: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val models = dao.getAll().filter { File(it.filePath).parentFile?.absolutePath != dir.absolutePath && File(it.filePath).isFile }
        var moved = 0
        models.forEachIndexed { i, m ->
            val src = File(m.filePath)
            progress("${src.name} (${i + 1}/${models.size})")
            if (dir.usableSpace < src.length() + 50L * 1024 * 1024) throw java.io.IOException("Not enough free space for ${src.name}")
            val dest = File(dir, src.name)
            if (!src.renameTo(dest)) {
                val tmp = File(dir, src.name + ".moving")
                src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                if (tmp.length() != src.length()) { tmp.delete(); throw java.io.IOException("Copy of ${src.name} was incomplete") }
                tmp.renameTo(dest)
                src.delete()
            }
            dao.upsert(m.copy(filePath = dest.absolutePath))
            dao.delete(m.filePath)
            moved++
        }
        moved
    }

    val installedModels: Flow<List<LocalModelEntity>> = dao.observeAll()

    suspend fun get(path: String): LocalModelEntity? = dao.get(path)

    suspend fun getMostRecentlyUsed(): LocalModelEntity? = dao.getMostRecentlyUsed()

    suspend fun markUsed(path: String) = dao.touchLastUsed(path, System.currentTimeMillis())

    suspend fun deleteModel(model: LocalModelEntity): Boolean = withContext(Dispatchers.IO) {
        val file = File(model.filePath)
        val deleted = !file.exists() || file.delete()
        if (deleted) dao.delete(model.filePath)
        deleted
    }

    suspend fun registerModel(
        file: File,
        displayName: String = file.nameWithoutExtension,
        hfModelId: String? = null,
        hfFilename: String? = null,
        sha256: String? = null,
        sha256Verified: Boolean = false,
        quantization: String? = inferQuantFromFilename(file.name),
        paramCount: Long? = null,
    ): LocalModelEntity {
        val entity = LocalModelEntity(
            filePath = file.absolutePath,
            displayName = displayName,
            hfModelId = hfModelId,
            hfFilename = hfFilename,
            sizeBytes = file.length(),
            quantization = quantization,
            paramCount = paramCount,
            sha256 = sha256,
            sha256Verified = sha256Verified,
        )
        dao.upsert(entity)
        return entity
    }

    /** Copies a SAF-picked GGUF file into app storage, computing its SHA-256 as it streams. */
    suspend fun importFromUri(uri: Uri, suggestedName: String): Result<LocalModelEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(modelsDir, suggestedName)
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                        }
                    }
                } ?: error("Could not open selected file")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                registerModel(dest, sha256 = sha)
            }
        }

    /** Rough estimate: weights + KV cache + activation/runtime overhead, in bytes. */
    fun estimateRamRequirementBytes(sizeOnDiskBytes: Long, contextLength: Int = 4096): Long {
        val kvCacheOverhead = contextLength.toLong() * 2 * 128 * 1024 // coarse per-layer estimate
        return (sizeOnDiskBytes * 1.15).toLong() + kvCacheOverhead + 256L * 1024 * 1024
    }

    fun availableMemoryBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    fun totalMemoryBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun fitsInMemory(model: LocalModelEntity, contextLength: Int = 4096): Boolean =
        estimateRamRequirementBytes(model.sizeBytes, contextLength) < availableMemoryBytes() * 0.85

    companion object {
        private val QUANT_PATTERN = Regex(
            "(Q[0-9]_[0-9A-Z_]+|IQ[0-9]_[A-Z]+|F16|F32|BF16)",
            RegexOption.IGNORE_CASE,
        )

        fun inferQuantFromFilename(filename: String): String? =
            QUANT_PATTERN.find(filename)?.value?.uppercase()
    }
}
