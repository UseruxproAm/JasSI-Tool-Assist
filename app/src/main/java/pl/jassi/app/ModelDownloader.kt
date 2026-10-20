package pl.jassi.app

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Pobiera model z internetu do filesDir/models/. Wznawia przerwane pobieranie (plik .part + nagłówek Range). */
object ModelDownloader {
    suspend fun download(ctx: Context, url: String, destName: String, onMb: (done: Long, total: Long) -> Unit) {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val part = File(dir, "$destName.part")
        val dest = File(dir, destName)
        var existing = if (part.exists()) part.length() else 0L

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (existing > 0) c.setRequestProperty("Range", "bytes=$existing-")
        try {
            c.connect()
            val code = c.responseCode
            if (code == 416) {                       // plik .part już kompletny
                dest.delete(); part.renameTo(dest); return
            }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val append = code == 206
            if (!append) existing = 0
            val total = existing + c.contentLengthLong

            FileOutputStream(part, append).use { out ->
                c.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 16)
                    var done = existing
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= (1L shl 20)) { lastReport = done; onMb(done shr 20, total shr 20) }
                    }
                }
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("nie można zapisać pliku")
        } finally {
            c.disconnect()
        }
    }
}
