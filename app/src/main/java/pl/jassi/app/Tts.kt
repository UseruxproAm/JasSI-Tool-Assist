package pl.jassi.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class Tts(ctx: Context) {
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        engine = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                engine?.language = Locale("pl", "PL")
                engine?.setSpeechRate(1.05f)
                ready = true
            }
        }
    }

    suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val t = engine
        if (t == null || !ready) { cont.resume(Unit); return@suspendCancellableCoroutine }
        val id = UUID.randomUUID().toString()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { if (u == id && cont.isActive) cont.resume(Unit) }
            @Deprecated("deprecated") override fun onError(u: String?) { if (u == id && cont.isActive) cont.resume(Unit) }
        })
        t.speak(text.replace(Regex("[*_#`]"), ""), TextToSpeech.QUEUE_FLUSH, null, id)
        cont.invokeOnCancellation { t.stop() }
    }

    fun shutdown() { engine?.stop(); engine?.shutdown() }
}
