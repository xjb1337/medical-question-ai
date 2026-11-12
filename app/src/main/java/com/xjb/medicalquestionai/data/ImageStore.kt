package com.xjb.medicalquestionai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
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
        val boundsStream = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw ImageImportException("没有权限读取这张图片，请重新选择", e)
        } catch (e: Exception) {
            throw ImageImportException("无法读取这张图片，请重新选择或换一张图片", e)
        } ?: throw ImageImportException("无法读取这张图片，请重新选择或换一张图片")
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

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(originalWidth, originalHeight, MAX_SOURCE_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val pixelStream = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw ImageImportException("没有权限读取这张图片，请重新选择", e)
        } catch (e: Exception) {
            throw ImageImportException("无法再次读取这张图片，请重新选择", e)
        } ?: throw ImageImportException("无法再次读取这张图片，请重新选择")
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

        val token = imageToken()
        val sourceOutput = File(imagesDir, "$SOURCE_PREFIX$token.jpg")
        val displayOutput = File(imagesDir, "$DERIVED_PREFIX$token.jpg")
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
            bitmap = scaleDownTo(bitmap, MAX_SOURCE_DIMENSION)

            writeBitmap(sourceOutput, bitmap, SOURCE_JPEG_QUALITY)

            val displayBitmap = scaleDownTo(bitmap, MAX_DIMENSION)
            try {
                writeBitmap(displayOutput, displayBitmap, JPEG_QUALITY)
            } finally {
                if (displayBitmap !== bitmap) displayBitmap.recycle()
            }

            DraftImage(
                id = UUID.randomUUID().toString(),
                path = displayOutput.absolutePath,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = displayOutput.length(),
                warnings = importWarnings(originalWidth, originalHeight),
                sourcePath = sourceOutput.absolutePath
            )
        } catch (e: ImageImportException) {
            sourceOutput.delete()
            displayOutput.delete()
            throw e
        } catch (e: OutOfMemoryError) {
            sourceOutput.delete()
            displayOutput.delete()
            throw ImageImportException("图片过大，设备内存不足，请先缩小图片后重试", e)
        } catch (e: Exception) {
            sourceOutput.delete()
            displayOutput.delete()
            throw ImageImportException("图片处理失败，请重试或换一张图片", e)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun rotate(path: String, degrees: Int): ImageMeta = withContext(Dispatchers.IO) {
        val displayFile = File(path)
        require(displayFile.exists()) { "图片文件不存在" }
        val sourceFile = cropSource(displayFile)
        val source = decodeBounded(sourceFile, MAX_SOURCE_DIMENSION) ?: error("图片解码失败")
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        var upload: Bitmap? = null
        try {
            if (isOwnedImage(displayFile)) {
                val sourceTarget = sourceFileFor(displayFile)
                writeBitmap(sourceTarget, rotated, SOURCE_JPEG_QUALITY)
                upload = scaleDownTo(rotated, MAX_DIMENSION)
                writeBitmap(displayFile, upload, JPEG_QUALITY)
                ImageMeta(
                    width = rotated.width,
                    height = rotated.height,
                    sizeBytes = displayFile.length(),
                    sourcePath = sourceTarget.absolutePath
                )
            } else {
                writeBitmap(displayFile, rotated, SOURCE_JPEG_QUALITY)
                ImageMeta(rotated.width, rotated.height, displayFile.length())
            }
        } finally {
            if (upload != null && upload !== rotated) upload.recycle()
            rotated.recycle()
        }
    }

    suspend fun cropCopy(path: String, rect: android.graphics.RectF): DraftImage = withContext(Dispatchers.IO) {
        val displayFile = File(path)
        require(displayFile.exists()) { "图片文件不存在" }
        val cropped = decodeCrop(cropSource(displayFile), rect)
        val token = imageToken()
        val sourceOutput = File(imagesDir, "$SOURCE_PREFIX$token.jpg")
        val displayOutput = File(imagesDir, "$DERIVED_PREFIX$token.jpg")
        var upload: Bitmap? = null
        try {
            writeBitmap(sourceOutput, cropped, SOURCE_JPEG_QUALITY)
            upload = scaleDownTo(cropped, MAX_DIMENSION)
            writeBitmap(displayOutput, upload, JPEG_QUALITY)
            DraftImage(
                id = UUID.randomUUID().toString(),
                path = displayOutput.absolutePath,
                width = cropped.width,
                height = cropped.height,
                sizeBytes = displayOutput.length(),
                warnings = cropWarnings(cropped.width, cropped.height, keepOriginal = true),
                sourcePath = sourceOutput.absolutePath
            )
        } catch (e: Exception) {
            sourceOutput.delete()
            displayOutput.delete()
            throw e
        } finally {
            if (upload != null && upload !== cropped) upload.recycle()
            cropped.recycle()
        }
    }

    suspend fun cropInPlace(path: String, rect: android.graphics.RectF): ImageMeta = withContext(Dispatchers.IO) {
        val displayFile = File(path)
        require(displayFile.exists()) { "图片文件不存在" }
        val cropped = decodeCrop(cropSource(displayFile), rect)
        var upload: Bitmap? = null
        try {
            if (isOwnedImage(displayFile)) {
                val sourceTarget = sourceFileFor(displayFile)
                writeBitmap(sourceTarget, cropped, SOURCE_JPEG_QUALITY)
                upload = scaleDownTo(cropped, MAX_DIMENSION)
                writeBitmap(displayFile, upload, JPEG_QUALITY)
                ImageMeta(
                    width = cropped.width,
                    height = cropped.height,
                    sizeBytes = displayFile.length(),
                    warnings = cropWarnings(cropped.width, cropped.height, keepOriginal = false),
                    sourcePath = sourceTarget.absolutePath
                )
            } else {
                writeBitmap(displayFile, cropped, SOURCE_JPEG_QUALITY)
                ImageMeta(
                    width = cropped.width,
                    height = cropped.height,
                    sizeBytes = displayFile.length(),
                    warnings = cropWarnings(cropped.width, cropped.height, keepOriginal = false)
                )
            }
        } finally {
            if (upload != null && upload !== cropped) upload.recycle()
            cropped.recycle()
        }
    }

    fun handleFor(path: String): File = File(path)

    /** 删除展示图及其同 token 的高清源图；外部路径不会被删除。 */
    fun delete(path: String) {
        val file = ownedImage(path) ?: return
        val candidates = when {
            file.name.startsWith(DERIVED_PREFIX) -> listOf(file, sourceFileFor(file))
            file.name.startsWith(SOURCE_PREFIX) -> listOf(file, displayFileFor(file))
            else -> listOf(file)
        }
        candidates.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .forEach { runCatching { if (it.exists()) it.delete() } }
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

    /**
     * 上传入口的最后一道尺寸保险。新导入图片通常直接读取派生图；
     * 旧图或调用方误传高清源图时，会在缓存中派生一份 ≤1800 的 JPEG。
     */
    fun readBase64(path: String): String {
        val file = File(path)
        val bounds = decodeBounds(file) ?: error("图片文件不存在或无法读取")
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        val alreadyUploadReady = maxSide <= MAX_DIMENSION &&
            file.extension.lowercase() in JPEG_EXTENSIONS &&
            !file.name.startsWith(SOURCE_PREFIX)
        val bytes = if (alreadyUploadReady) {
            file.readBytes()
        } else {
            val upload = createUploadJpeg(file)
            try {
                upload.readBytes()
            } finally {
                upload.delete()
            }
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun totalBytes(paths: List<String>): Long = paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }

    private fun importWarnings(originalWidth: Int, originalHeight: Int): List<String> = buildList {
        if (originalWidth < LOW_RES_THRESHOLD || originalHeight < LOW_RES_THRESHOLD) {
            add("分辨率偏低，建议靠近重拍以保证文字清晰")
        }
        if (maxOf(originalWidth, originalHeight) > HIGH_RES_THRESHOLD) {
            add("已保留高清源图，上传时会自动压缩")
        }
    }

    private fun cropWarnings(width: Int, height: Int, keepOriginal: Boolean): List<String> = buildList {
        if (keepOriginal) add("由上一张图片裁剪生成，原图已保留")
        if (minOf(width, height) < MIN_CROP_SHORT_SIDE) {
            add(
                "裁剪后短边仅 ${minOf(width, height)}px（低于 ${MIN_CROP_SHORT_SIDE}px），" +
                    "文字可能不清晰，建议扩大裁剪范围或重拍"
            )
        }
    }

    private fun createUploadJpeg(file: File): File {
        val bounds = decodeBounds(file) ?: error("图片解码失败")
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateUploadSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("图片解码失败")
        val target = File.createTempFile("upload_", ".jpg", context.cacheDir)
        try {
            bitmap = scaleDownTo(bitmap, MAX_DIMENSION)
            writeBitmap(target, bitmap, JPEG_QUALITY)
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeCrop(sourceFile: File, rect: android.graphics.RectF): Bitmap {
        val bounds = decodeBounds(sourceFile) ?: error("图片解码失败")
        val region = normalizedCrop(bounds.outWidth, bounds.outHeight, rect)
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(sourceFile.absolutePath, false)
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            decoder.decodeRegion(
                android.graphics.Rect(region[0], region[1], region[0] + region[2], region[1] + region[3]),
                options
            ) ?: error("裁剪图片解码失败")
        } finally {
            decoder.recycle()
        }
    }

    private fun decodeBounded(file: File, maxDimension: Int): Bitmap? {
        val bounds = decodeBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return scaleDownTo(decoded, maxDimension)
    }

    private fun decodeBounds(file: File): BitmapFactory.Options? {
        if (!file.isFile) return null
        return BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun writeBitmap(file: File, bitmap: Bitmap, quality: Int) {
        val parent = file.parentFile ?: error("图片目录不存在")
        parent.mkdirs()
        val temp = File.createTempFile("${file.nameWithoutExtension}_", ".tmp", parent)
        try {
            val compressed = FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            if (!compressed || temp.length() <= 0L) {
                throw ImageImportException("图片保存失败，请重试")
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun normalizedCrop(width: Int, height: Int, rect: android.graphics.RectF): IntArray {
        val left = (rect.left.coerceIn(0f, 1f) * width).toInt().coerceIn(0, width - 1)
        val top = (rect.top.coerceIn(0f, 1f) * height).toInt().coerceIn(0, height - 1)
        val right = (rect.right.coerceIn(0f, 1f) * width).toInt().coerceIn(left + 1, width)
        val bottom = (rect.bottom.coerceIn(0f, 1f) * height).toInt().coerceIn(top + 1, height)
        return intArrayOf(left, top, right - left, bottom - top)
    }

    private fun scaleDownTo(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / maxSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun calculateUploadSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DIMENSION * 2) {
            sample *= 2
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

    private fun cropSource(displayFile: File): File = sourceFileFor(displayFile).takeIf { it.isFile } ?: displayFile

    private fun sourceFileFor(file: File): File {
        if (file.name.startsWith(SOURCE_PREFIX)) return file
        val stem = file.name.removePrefix(DERIVED_PREFIX)
        return File(file.parentFile, "$SOURCE_PREFIX$stem")
    }

    private fun displayFileFor(file: File): File {
        val stem = file.name.removePrefix(SOURCE_PREFIX)
        return File(file.parentFile, "$DERIVED_PREFIX$stem")
    }

    private fun ownedImage(path: String): File? {
        val file = File(path)
        val parent = runCatching { file.parentFile?.canonicalFile }.getOrNull() ?: return null
        val ownDir = runCatching { imagesDir.canonicalFile }.getOrNull() ?: return null
        return file.takeIf { parent == ownDir }
    }

    private fun isOwnedImage(file: File): Boolean = ownedImage(file.absolutePath) != null

    private fun imageToken(): String = "${System.currentTimeMillis()}_${UUID.randomUUID()}"

    companion object {
        const val MAX_IMAGES = 9
        const val MAX_DIMENSION = 1800
        const val MAX_SOURCE_DIMENSION = 4096
        const val JPEG_QUALITY = 86
        const val SOURCE_JPEG_QUALITY = 95
        const val MIN_CROP_SHORT_SIDE = 400
        private const val LOW_RES_THRESHOLD = 900
        private const val HIGH_RES_THRESHOLD = 3200
        private const val SOURCE_PREFIX = "src_"
        private const val DERIVED_PREFIX = "q_"
        private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")
    }
}

data class ImageMeta(
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val warnings: List<String> = emptyList(),
    val sourcePath: String? = null
)
