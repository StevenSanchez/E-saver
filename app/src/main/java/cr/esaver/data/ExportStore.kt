/*
 * Exportación del respaldo CSV.
 *
 * El archivo se escribe en cache/exports y se comparte con FileProvider para
 * que el usuario pueda enviarlo por correo, Drive o cualquier app instalada.
 */
package cr.esaver.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Escribe el CSV en caché y devuelve la URI compartible. */
object ExportStore {
    fun writeCsv(context: Context, fileName: String, content: String): Uri? {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        return runCatching {
            file.writeText("\uFEFF" + content, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
}
