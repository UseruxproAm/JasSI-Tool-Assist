package pl.jassi.app

import android.content.Context
import android.net.Uri
import java.io.File

object ModelStore {
    private fun dirs(ctx: Context) = listOfNotNull(
        File(ctx.filesDir, "models"),
        ctx.getExternalFilesDir(null)?.let { File(it, "models") }   // adb push ... /sdcard/Android/data/pl.jassi.app/files/models/
    )

    /** najnowszy plik o danym rozszerzeniu (.gguf / .bin) */
    fun find(ctx: Context, ext: String): File? =
        dirs(ctx).flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.name.endsWith(ext, true) && it.length() > 1_000_000 }
            .maxByOrNull { it.lastModified() }

    fun import(ctx: Context, uri: Uri, destName: String, onMb: (Long) -> Unit) {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val tmp = File(dir, "$destName.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    onMb(total shr 20)
                }
            }
        }
        val dest = File(dir, destName)
        dest.delete()
        tmp.renameTo(dest)
    }
}
