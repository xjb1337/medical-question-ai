package com.xjb.medicalquestionai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CaptureTarget(val file: File, val uri: Uri)

class ImageImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ImageStore(private val context: Context) {

    private val imagesDir: File
        get() = File(context.filesDir, "question_images").apply { mkdirs() }

    suspend fun importImage(uri: Uri): DraftImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds=true intentionally returns no Bitmap. Check only
        // whether the stream opens here; decoding success is judged below.
        val boundsStream = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw ImageImportException("没有权限读取这张图片，请重新选择", e)
        } catch (e: Exception) {
            throw ImageImportException("无法读取这张图片，请重新选择或换一张图片", e)
        }
        if (boundsStream == null) {
            throw ImageImportException("无法读取这张图片，请重新选择或换一张图片")
        }
        try {
            boundsStream.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        } catch (e: Exception) {
            throw ImageImportException("无法读取这张图片，请重新选择或换一张图片", e)
        }

        val originalWidth = bounds.outWidth
        val originalHeight = bounds.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) {
            throw ImageImportException("图片格式无法识别，请换一张 JPG、PNG 或 WEBP 图片")
        }

        val sample = calculateSampleSize(originalWidth, originalHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val pixelStream = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw ImageImportException("没有权限读取这张图片，请重新选择", e)
        } catch (e: Exception) {
            throw ImageImportException("无法再次读取这张图片，请重新选择", e)
        }
        if (pixelStream == null) {
            throw ImageImportException("无法再次读取这张图片，请重新选择")
        }
        var bitmap: Bitmap? = try {
            pixelStream.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
        } catch (e: SecurityException) {
            throw ImageImportException("没有权限读取这张图片，请重新选择", e)
        } catch (e: OutOfMemoryError) {
            throw ImageImportException("图片过大，设备内存不足，请先缩小图片后重试", e)
        } catch (e: Exception) {
            throw ImageImportException("图片读取失败，请重新选择或换一张图片", e)
        }
        if (bitmap == null) throw ImageImportException("图片解码失败，请换一张图片重试")

        var target: File? = null
        try {
            val orientation = runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            bitmap = applyOrientation(bitmap, orientation)

            val maxSide = maxOf(bitmap.width, bitmap.height)
            if (maxSide > MAX_DIMENSION) {
                val ratio = MAX_DIMENSION.toFloat() / maxSide
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            val output = File(imagesDir, "q_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
            target = output
            val compressed = FileOutputStream(output).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!compressed || output.length() <= 0L) {
                throw ImageImportException("图片保存失败，请重试")
            }
            val warnings = buildList {
                if (originalWidth < LOW_RES_THRESHOLD || originalHeight < LOW_RES_THRESHOLD) {
                    add("分辨率偏低，建议靠近重拍以保证文字清晰")
                }
                if (maxOf(originalWidth, originalHeight) > HIGH_RES_THRESHOLD) {
                    add("原图较大，已自动压缩后上传")
                }
            }
            DraftImage(
                id = UUID.randomUUID().toString(),
                path = output.absolutePath,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = output.length(),
                warnings = warnings
            )
        } catch (e: ImageImportException) {
            target?.delete()
            throw e
        } catch (e: OutOfMemoryError) {
            target?.delete()
            throw ImageImportException("图片过大，设备内存不足，请先缩小图片后重试", e)
        } catch (e: Exception) {
            target?.delete()
            throw ImageImportException("图片处理失败，请重试或换一张图片", e)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun rotate(path: String, degrees: Int): ImageMeta = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.exists()) { "图片文件不存在" }
        val source = BitmapFactory.decodeFile(path) ?: error("图片解码失败")
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        writeBitmap(file, rotated)
        val width = rotated.width
        val height = rotated.height
        if (rotated !== source) source.recycle()
        rotated.recycle()
        ImageMeta(width, height, file.length())
    }

    suspend fun cropCopy(path: String, rect: android.graphics.RectF): DraftImage = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.exists()) { "图片文件不存在" }
        val source = BitmapFactory.decodeFile(path) ?: error("图片解码失败")
        var cropped: Bitmap? = null
        var target: File? = null
        try {
            val bounds = normalizedCrop(source, rect)
            val croppedBitmap = Bitmap.createBitmap(source, bounds[0], bounds[1], bounds[2], bounds[3])
            cropped = croppedBitmap
            target = File(imagesDir, "q_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
            writeBitmap(target, croppedBitmap)
            if (target.length() <= 0L) throw ImageImportException("裁剪图片保存失败，请重试")
            DraftImage(
                id = UUID.randomUUID().toString(),
                path = target.absolutePath,
                width = croppedBitmap.width,
                height = croppedBitmap.height,
                sizeBytes = target.length(),
                warnings = listOf("由上一张图片裁剪生成，原图已保留")
            )
        } catch (e: Exception) {
            target?.delete()
            throw e
        } finally {
            if (cropped != null && cropped !== source) cropped.recycle()
            source.recycle()
        }
    }

    suspend fun cropInPlace(path: String, rect: android.graphics.RectF): ImageMeta = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.exists()) { "图片文件不存在" }
        val source = BitmapFactory.decodeFile(path) ?: error("图片解码失败")
        var cropped: Bitmap? = null
        try {
            val bounds = normalizedCrop(source, rect)
            val croppedBitmap = Bitmap.createBitmap(source, bounds[0], bounds[1], bounds[2], bounds[3])
            cropped = croppedBitmap
            writeBitmap(file, croppedBitmap)
            if (file.length() <= 0L) throw ImageImportException("裁剪图片保存失败，请重试")
            ImageMeta(croppedBitmap.width, croppedBitmap.height, file.length())
        } finally {
            if (cropped != null && cropped !== source) cropped.recycle()
            source.recycle()
        }
    }

    fun handleFor(path: String): File = File(path)

    fun delete(path: String) {
        runCatching { File(path).takeIf { it.exists() && it.parentFile?.canonicalPath == imagesDir.canonicalPath }?.delete() }
    }

    fun deleteAll(paths: Iterable<String>) {
        paths.forEach(::delete)
    }

    fun createCaptureTarget(): CaptureTarget {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File.createTempFile("camera_", ".jpg", dir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return CaptureTarget(file, uri)
    }

    fun deleteCapture(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    fun readBase64(path: String): String {
        val bytes = File(path).readBytes()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun totalBytes(paths: List<String>): Long = paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }

    private fun writeBitmap(file: File, bitmap: Bitmap) {
        val temp = File(file.parentFile, "${file.nameWithoutExtension}_tmp.jpg")
        FileOutputStream(temp).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun normalizedCrop(source: Bitmap, rect: android.graphics.RectF): IntArray {
        val left = (rect.left.coerceIn(0f, 1f) * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (rect.top.coerceIn(0f, 1f) * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (rect.right.coerceIn(0f, 1f) * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (rect.bottom.coerceIn(0f, 1f) * source.height).toInt().coerceIn(top + 1, source.height)
        return intArrayOf(left, top, right - left, bottom - top)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var largest = maxOf(width, height)
        while (largest / 2 >= MAX_DIMENSION) {
            sample *= 2
            largest /= 2
        }
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    companion object {
        const val MAX_IMAGES = 9
        const val MAX_DIMENSION = 1800
        const val JPEG_QUALITY = 86
        private const val LOW_RES_THRESHOLD = 900
        private const val HIGH_RES_THRESHOLD = 3200
    }
}

data class ImageMeta(val width: Int, val height: Int, val sizeBytes: Long)
