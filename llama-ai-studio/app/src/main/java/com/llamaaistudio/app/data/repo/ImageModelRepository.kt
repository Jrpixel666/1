package com.llamaaistudio.app.data.repo

import android.content.Context
import com.llamaaistudio.app.data.db.LocalImageModelDao
import com.llamaaistudio.app.data.db.LocalImageModelEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class ImageModelRepository(
    context: Context,
    private val dao: LocalImageModelDao,
) {
    val modelsDir: File = File(context.filesDir, "image_models").apply { mkdirs() }

    val installedModels: Flow<List<LocalImageModelEntity>> = dao.observeAll()

    suspend fun markUsed(path: String) = dao.touchLastUsed(path, System.currentTimeMillis())

    suspend fun getMostRecentlyUsed(): LocalImageModelEntity? = dao.getMostRecentlyUsed()

    suspend fun deleteModel(model: LocalImageModelEntity): Boolean {
        val file = File(model.filePath)
        val deleted = !file.exists() || file.delete()
        if (deleted) dao.delete(model.filePath)
        return deleted
    }

    suspend fun registerModel(
        file: File,
        displayName: String = file.nameWithoutExtension,
        hfModelId: String? = null,
        hfFilename: String? = null,
        sha256: String? = null,
        sha256Verified: Boolean = false,
        quantization: String? = ModelRepository.inferQuantFromFilename(file.name),
    ): LocalImageModelEntity {
        val entity = LocalImageModelEntity(
            filePath = file.absolutePath,
            displayName = displayName,
            hfModelId = hfModelId,
            hfFilename = hfFilename,
            sizeBytes = file.length(),
            quantization = quantization,
            sha256 = sha256,
            sha256Verified = sha256Verified,
        )
        dao.upsert(entity)
        return entity
    }

    /** Diffusion checkpoints decode/denoise in float32 working buffers; rule of thumb ~1.3x file size. */
    fun estimateRamRequirementBytes(sizeOnDiskBytes: Long): Long =
        (sizeOnDiskBytes * 1.3).toLong() + 512L * 1024 * 1024
}
