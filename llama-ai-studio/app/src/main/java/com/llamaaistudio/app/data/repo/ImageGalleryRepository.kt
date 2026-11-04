package com.llamaaistudio.app.data.repo

import com.llamaaistudio.app.data.db.GeneratedImageDao
import com.llamaaistudio.app.data.db.GeneratedImageEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class ImageGalleryRepository(private val dao: GeneratedImageDao) {
    val images: Flow<List<GeneratedImageEntity>> = dao.observeAll()

    suspend fun record(image: GeneratedImageEntity): Long = dao.insert(image)

    suspend fun delete(image: GeneratedImageEntity) {
        File(image.filePath).delete()
        dao.delete(image)
    }
}
