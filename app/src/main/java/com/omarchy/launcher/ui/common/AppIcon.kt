package com.omarchy.launcher.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.data.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small process-wide cache so scrolling the drawer doesn't re-decode the same icon repeatedly. */
private object IconBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(300)

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) = cache.put(key, bitmap)
}

private const val ICON_SIZE_PX = 128

/** Loads an app's icon off the main thread and renders it, falling back to nothing if missing. */
@Composable
fun AppIcon(app: AppInfo, appRepository: AppRepository, modifier: Modifier = Modifier) {
    val bitmapState = produceState<ImageBitmap?>(initialValue = IconBitmapCache.get(app.key), key1 = app.key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                val drawable = appRepository.loadIcon(app) ?: return@withContext null
                val bitmap = drawable.toBitmap(width = ICON_SIZE_PX, height = ICON_SIZE_PX, config = Bitmap.Config.ARGB_8888)
                bitmap.asImageBitmap().also { IconBitmapCache.put(app.key, it) }
            }
        }
    }

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = app.label, modifier = modifier)
    }
}
