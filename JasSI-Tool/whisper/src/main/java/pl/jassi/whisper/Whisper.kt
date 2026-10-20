package pl.jassi.whisper

object Whisper {
    init { System.loadLibrary("jassi_whisper") }
    /** zwraca uchwyt (0 = błąd) */
    external fun load(path: String): Long
    external fun transcribe(handle: Long, audio: FloatArray, lang: String, threads: Int, prompt: String): String
    external fun free(handle: Long)
}
