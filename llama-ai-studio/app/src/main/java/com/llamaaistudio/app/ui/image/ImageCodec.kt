package com.llamaaistudio.app.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Prepares a gallery image for editing: RGB888 for img2img, a small JPEG for the AI. */
object ImageCodec {
    class Prepared(val rgb: ByteArray, val jpegForAi: ByteArray)

    fun prepare(file: File, width: Int, height: Int): Prepared? {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val scaled = if (src.width == width && src.height == height) src else Bitmap.createScaledBitmap(src, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[i * 3] = (p shr 16 and 0xFF).toByte()
            rgb[i * 3 + 1] = (p shr 8 and 0xFF).toByte()
            rgb[i * 3 + 2] = (p and 0xFF).toByte()
        }
        // Vision models only need a modest preview; keeps the request small.
        val maxSide = 768
        val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
        val preview = if (scale < 1f) Bitmap.createScaledBitmap(scaled, (width * scale).toInt(), (height * scale).toInt(), true) else scaled
        val jpeg = ByteArrayOutputStream().use { out ->
            preview.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        return Prepared(rgb, jpeg)
    }
}
