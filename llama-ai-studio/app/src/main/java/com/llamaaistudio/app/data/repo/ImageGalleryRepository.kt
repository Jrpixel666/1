package com.llamaaistudio.app.data.repo

import com.llamaaistudio.app.data.db.GeneratedImageDao
import com.llamaaistudio.app.data.db.GeneratedImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class ImageGalleryRepository(private val dao: GeneratedImageDao) {
    val images: Flow<List<GeneratedImageEntity>> = dao.observeAll()

    suspend fun record(image: GeneratedImageEntity): Long = dao.insert(image)

    suspend fun delete(image: GeneratedImageEntity) {
        withContext(Dispatchers.IO) { File(image.filePath).delete() }
        dao.delete(image)
    }
}
