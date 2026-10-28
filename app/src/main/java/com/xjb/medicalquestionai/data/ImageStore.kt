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

class ImageStore(private val context: Context) {

    private val imagesDir: File
        get() = File(context.filesDir, "question_images").apply { mkdirs() }

    suspend fun importImage(uri: Uri): DraftImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("无法读取这张图片")
        val originalWidth = bounds.outWidth
        val originalHeight = bounds.outHeight
        if (originalWidth <= 0 || originalHeight <= 0) error("图片格式无法识别")

        val sample = calculateSampleSize(originalWidth, originalHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("图片解码失败")

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
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        }

        val target = File(imagesDir, "q_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

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
            path = target.absolutePath,
            width = width,
            height = height,
            sizeBytes = target.length(),
            warnings = warnings
        )
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

    suspend fun crop(path: String, rect: android.graphics.RectF): ImageMeta = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.exists()) { "图片文件不存在" }
        val source = BitmapFactory.decodeFile(path) ?: error("图片解码失败")
        val left = (rect.left.coerceIn(0f, 1f) * source.width).toInt()
        val top = (rect.top.coerceIn(0f, 1f) * source.height).toInt()
        val right = (rect.right.coerceIn(0f, 1f) * source.width).toInt()
        val bottom = (rect.bottom.coerceIn(0f, 1f) * source.height).toInt()
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(source, left, top, width, height)
        writeBitmap(file, cropped)
        if (cropped !== source) source.recycle()
        cropped.recycle()
        ImageMeta(width, height, file.length())
    }

    fun handleFor(path: String): File = File(path)

    fun delete(path: String) {
        runCatching { File(path).takeIf { it.exists() && it.parentFile?.canonicalPath == imagesDir.canonicalPath }?.delete() }
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
