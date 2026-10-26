package io.github.Calvariaa.kitchennotes.data

/**
 * 菜品照片的字节级处理。
 *
 * 上传链路（设备存储 → 仓库）：
 *  1. `ImageIngestor.readBytes` 把 content:// 或 file:// 全读进内存（都做过上限保护）。
 *  2. `normalize` 按最长边缩放 + JPEG 重编码：手机原图 5-12MB，直接进 git 会让每次 push 巨大；
 *     统一到 [DEFAULT_MAX_EDGE] 后单张 200-500KB，视觉细节仍然够模型识别写法和食材。
 *  3. `RecipeRepository.storeImage` 按 SHA-256 命名落盘，天然去重。
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object ImageIngestor {

    const val DEFAULT_MAX_EDGE = 1600
    private const val HARD_LIMIT_BYTES = 64L * 1024 * 1024

    suspend fun readBytes(ctx: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > HARD_LIMIT_BYTES) error("图片超过 64MB，换一张或者先压缩")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error("读取图片失败：无法打开 $uri")
    }

    suspend fun readFile(ctx: Context, file: File): ByteArray = withContext(Dispatchers.IO) {
        if (file.length() > HARD_LIMIT_BYTES) error("图片超过 64MB")
        file.readBytes()
    }

    /**
     * 缩放 + 纠正 EXIF 方向 + 重编码为 JPEG。
     * @return 处理后的 JPEG 字节；若原图本身就是需要的小图，也统一走重编码，保证格式一致。
     */
    suspend fun normalize(bytes: ByteArray, maxEdge: Int = DEFAULT_MAX_EDGE): ByteArray =
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("这张图解码不了，可能格式不支持")

            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var scale = 1
            if (longest > maxEdge) {
                scale = 1
                while (longest / (scale * 2) >= maxEdge) scale *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: error("这张图解码不了，可能格式不支持")

            val rotated = sampleDown(sampled, maxEdge)
            val upright = applyExifRotation(rotated, bytes)

            ByteArrayOutputStream().use { out ->
                upright.compress(Bitmap.CompressFormat.JPEG, 88, out)
                if (rotated !== upright) upright.recycle()
                if (sampled !== rotated) sampled.recycle()
                out.toByteArray()
            }
        }

    private fun sampleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun applyExifRotation(src: Bitmap, original: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(original.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }.getOrDefault(src)
    }

    fun mimeOfExtension(peek: ByteArray): String = when {
        peek.size >= 3 && peek[0] == 0xFF.toByte() && peek[1] == 0xD8.toByte() -> "image/jpeg"
        peek.size >= 8 && peek[0] == 0x89.toByte() && peek[1] == 0x50.toByte() -> "image/png"
        peek.size >= 4 && peek[0] == 'G'.code.toByte() && peek[1] == 'I'.code.toByte() -> "image/gif"
        peek.size >= 12 && peek[8] == 'W'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }
}
