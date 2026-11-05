/*
 * Fotos de cargas y recibos.
 *
 * Se guardan dentro de la app (files/photos), se corrigen según el EXIF y se
 * reducen a 2200 px para no llenar el teléfono. Las fotos huérfanas se borran
 * al recargar los datos.
 */
package cr.esaver.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/** Guarda las fotos dentro de la app y las reduce para que no ocupen demasiado. */
class PhotoStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun importFromUri(uri: Uri): String? {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { decodeSampled(it, MAX_DIMENSION) }
        }.getOrNull() ?: return null

        return save(applyOrientation(bitmap, orientation))
    }

    fun importFromFile(file: File): String? {
        if (!file.exists()) return null
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bitmap = runCatching { decodeSampled(file, MAX_DIMENSION) }.getOrNull() ?: return null
        return save(applyOrientation(bitmap, orientation))
    }

    private fun save(bitmap: Bitmap): String? {
        val target = File(dir, "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        return runCatching {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            target.absolutePath
        }.getOrNull()
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (file.parentFile?.canonicalPath == dir.canonicalPath) {
            runCatching { file.delete() }
        }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Borra fotos que ya no están asociadas a ninguna carga ni recibo. */
    fun cleanup(usedPaths: Set<String>) {
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in usedPaths) runCatching { file.delete() }
        }
    }

    /** Para miniaturas y pantalla completa: decodifica solo lo necesario. */
    fun load(path: String?, maxDimension: Int = 1080): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { decodeSampled(file, maxDimension) }.getOrNull()
    }

    private fun decodeSampled(file: File, maxDimension: Int): Bitmap? =
        file.inputStream().use { decodeSampled(it, maxDimension) }

    private fun decodeSampled(input: InputStream, maxDimension: Int): Bitmap? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
        }
        if (matrix.isIdentity) return bitmap
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    companion object {
        private const val MAX_DIMENSION = 2200
        private const val JPEG_QUALITY = 88
    }
}
