package pl.jassi.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.jassi.llama.Llama
import pl.jassi.whisper.Whisper
import java.io.File
import java.io.IOException

/** Cały "mózg" aplikacji: modele, nagrywanie, rozpoznawanie, komendy, odpowiedzi. Żyje niezależnie od Activity. */
object Engine {
    interface Ui {
        fun onState(text: String, active: Boolean)
        fun onLog(who: String, msg: String)
    }

    private const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val GEMMA_URL = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-IQ4_NL.gguf"

    val WHISPER_FILES = linkedMapOf(
        "base" to "ggml-base-q5_1.bin",
        "small" to "ggml-small-q5_1.bin",
        "turbo" to "ggml-large-v3-turbo-q5_0.bin"
    )
    val WHISPER_LABELS = linkedMapOf(
        "base" to "Base – szybki, słabszy (60 MB)",
        "small" to "Small – dobry kompromis (190 MB)  [domyślny]",
        "turbo" to "Large-v3-turbo – najlepszy, wolniejszy (574 MB)"
    )

    /** Podpowiedź dla Whispera (poprawia rozpoznawanie poleceń i nazwy asystenta). */
    private const val PROMPT = "Rozmowa z asystentem JasSI. Polecenia: otwórz przeglądarkę, zadzwoń do mamy, wyślij SMS, włącz latarkę."

    private lateinit var app: Context
    lateinit var tts: Tts
        private set
    lateinit var actions: Actions
        private set
    private val brain = Brain()

    @Volatile var whisperHandle = 0L
    @Volatile var llamaReady = false
    var ui: Ui? = null
    val transcript = StringBuilder()
    @Volatile var lastState = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var inited = false
    private var started = false
    private var talkJob: Job? = null
    private var contJob: Job? = null
    private var dlJob: Job? = null
    private const val THREADS = 4

    val continuous: Boolean get() = contJob?.isActive == true

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        tts = Tts(app)
        actions = Actions(app)
    }

    // ---------------------------------------------------------------- UI helpers
    private fun state(text: String, active: Boolean = false) { lastState = text; ui?.onState(text, active) }
    private fun stateAsync(text: String, active: Boolean = true) { mainHandler.post { state(text, active) } }
    private fun log(who: String, msg: String) { transcript.append(who).append(": ").append(msg).append("\n\n"); ui?.onLog(who, msg) }

    // ---------------------------------------------------------------- modele
    private fun modelsDir() = File(app.filesDir, "models").apply { mkdirs() }
    private fun prefs() = app.getSharedPreferences("jassi", Context.MODE_PRIVATE)
    fun whisperChoice(): String = prefs().getString("whisper_choice", "small") ?: "small"
    private fun managedWhisper() = File(modelsDir(), "whisper.bin").exists()
    private fun whisperHave(): String = prefs().getString("whisper_have", "base") ?: "base"

    private fun whisperFile(): File? =
        File(modelsDir(), "whisper.bin").takeIf { it.exists() && it.length() > 1_000_000 } ?: ModelStore.find(app, ".bin")
    private fun gemmaFile(): File? =
        File(modelsDir(), "gemma.gguf").takeIf { it.exists() && it.length() > 1_000_000 } ?: ModelStore.find(app, ".gguf")

    private fun needsWhisperDownload() = whisperFile() == null || (managedWhisper() && whisperHave() != whisperChoice())

    private fun readyText(): String = when {
        whisperHandle == 0L -> "Brak modelu Whisper – kliknij „Pobierz modele”."
        !llamaReady -> "Brak modelu Gemma – działają tylko polecenia."
        needsWhisperDownload() -> "Gotowy. Dostępny lepszy Whisper – kliknij „Pobierz modele”."
        continuous -> "Słucham… powiedz „JasSI” i polecenie."
        else -> "Gotowy. Dotknij kółka albo włącz tryb głośnomówiący."
    }

    private suspend fun loadModels() {
        state("Ładuję modele…", true)
        withContext(Dispatchers.IO) {
            if (whisperHandle == 0L) whisperFile()?.let { whisperHandle = Whisper.load(it.absolutePath) }
            if (!llamaReady) gemmaFile()?.let { llamaReady = Llama.load(it.absolutePath) }
        }
        state(readyText(), false)
    }

    /** Wołane przy starcie aplikacji / usługi: ładuje modele, a na Wi-Fi dociąga brakujące lub lepsze. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            loadModels()
            if (needsWhisperDownload() || gemmaFile() == null) {
                val cm = app.getSystemService(ConnectivityManager::class.java)
                if (cm.activeNetwork != null && !cm.isActiveNetworkMetered) downloadModels()
                else state("Modele do pobrania (połącz z Wi-Fi i kliknij „Pobierz modele”).", false)
            }
        }
    }

    fun chooseWhisper(key: String) {
        prefs().edit().putString("whisper_choice", key).apply()
        downloadModels()
    }

    fun downloadModels() {
        if (dlJob?.isActive == true) return
        dlJob = scope.launch {
            try {
                if (needsWhisperDownload()) {
                    val choice = whisperChoice()
                    val p = prefs()
                    if (p.getString("whisper_part", "") != choice) {          // nie wznawiaj części innego modelu
                        File(modelsDir(), "whisper.bin.part").delete()
                        p.edit().putString("whisper_part", choice).apply()
                    }
                    state("Pobieram Whisper…", true)
                    withContext(Dispatchers.IO) {
                        ModelDownloader.download(app, HF + WHISPER_FILES[choice], "whisper.bin") { d, t ->
                            stateAsync("Pobieram Whisper ($choice)… $d / $t MB")
                        }
                    }
                    if (whisperHandle != 0L) {
                        val h = whisperHandle; whisperHandle = 0L
                        withContext(Dispatchers.IO) { Whisper.free(h) }
                    }
                    p.edit().putString("whisper_have", choice).apply()
                }
                if (gemmaFile() == null) {
                    state("Pobieram Gemmę…", true)
                    withContext(Dispatchers.IO) {
                        ModelDownloader.download(app, GEMMA_URL, "gemma.gguf") { d, t -> stateAsync("Pobieram Gemmę… $d / $t MB") }
                    }
                }
                loadModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                state("Błąd pobierania (${e.message}). Kliknij „Pobierz modele” – wznowi od miejsca przerwania.", false)
            }
        }
    }

    // ---------------------------------------------------------------- rozpoznawanie
    private suspend fun transcribe(audio: FloatArray): String = withContext(Dispatchers.Default) {
        Whisper.transcribe(whisperHandle, audio, "pl", THREADS, PROMPT).trim()
    }

    private val HALLU = listOf("dziekuje za ogladanie", "napisy", "amara", "subskrybuj", "do zobaczenia", "dziekuje za uwage")

    private fun looksLikeNoise(text: String): Boolean {
        val n = Util.norm(text)
        if (n.length < 2) return true
        if (HALLU.any { n.contains(it) }) return true
        val p = Util.norm(PROMPT)
        if (n.length > 8 && p.contains(n)) return true            // Whisper odpowiedział własną podpowiedzią
        return false
    }

    // ---------------------------------------------------------------- słowo-klucz
    private val WAKE_FILLERS = setOf("hej", "hey", "ok", "okej", "halo", "czesc", "witaj", "sluchaj")

    private fun isWake(w: String): Boolean {
        val n = Util.norm(w)
        if (n.isEmpty()) return false
        return n.startsWith("jasi") || n.startsWith("jassi") || Util.lev(n, "jasi") <= 1 || Util.lev(n, "jassi") <= 1 ||
            Util.lev(n, "jasiu") <= 1 || Util.lev(n, "jasio") <= 1
    }

    /** null = brak słowa-klucza; "" = samo słowo-klucz; inaczej – reszta polecenia. */
    fun stripWake(text: String): String? {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in 0 until minOf(3, tokens.size)) {
            val tok = tokens[i].trim(',', '.', '!', '?', ':')
            if (isWake(tok)) {
                val before = tokens.subList(0, i).map { Util.norm(it) }
                if (before.all { it in WAKE_FILLERS }) return tokens.drop(i + 1).joinToString(" ").trimStart(',', '.', ':', ' ')
            }
        }
        return null
    }

    // ---------------------------------------------------------------- przetwarzanie polecenia
    private suspend fun processText(text: String) {
        log("Ty", text)
        val reply = actions.handle(text) ?: if (llamaReady) { state("Myślę…", true); brain.reply(text) }
            else "Nie rozumiem tego polecenia, a model rozmowy nie jest wczytany."
        log("JasSI", reply)
        state("Mówię…", true)
        tts.speak(reply)

        var hops = 0
        while (actions.awaitingReply && hops < 2) {         // JasSI dopytał (np. który kontakt) – słuchamy odpowiedzi
            hops++
            state("Słucham odpowiedzi…", true)
            val a = Recorder.record(80)
            val t2 = if (a.isEmpty()) "" else transcribe(a)
            if (t2.isBlank() || looksLikeNoise(t2)) { actions.cancelPending(); log("JasSI", "Nie usłyszałem odpowiedzi. Anuluję."); break }
            log("Ty", t2)
            val r2 = actions.handle(t2) ?: "Nie zrozumiałem."
            log("JasSI", r2)
            state("Mówię…", true)
            tts.speak(r2)
        }
        actions.cancelPending()
    }

    // ---------------------------------------------------------------- tryb dotyku
    fun toggleTalk() {
        if (talkJob?.isActive == true) { talkJob?.cancel(); return }
        if (whisperHandle == 0L) { state(readyText(), false); return }
        talkJob = scope.launch {
            try {
                state("Słucham…", true)
                val audio = Recorder.record(60)
                if (audio.isEmpty()) { log("JasSI", "(cisza)"); return@launch }
                state("Rozpoznaję…", true)
                val text = transcribe(audio)
                if (text.isBlank() || looksLikeNoise(text)) { log("JasSI", "(nie zrozumiałem)"); return@launch }
                // jeśli ktoś powie „JasSI, otwórz…” przy dotyku – słowo-klucz ignorujemy
                val cmd = stripWake(text)?.takeIf { it.isNotBlank() } ?: text
                processText(cmd)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Błąd", e.message ?: e.toString())
            } finally {
                state(readyText(), false)
            }
        }
    }

    // ---------------------------------------------------------------- tryb głośnomówiący
    fun startContinuous() {
        if (continuous) return
        contJob = scope.launch {
            try {
                while (isActive && whisperHandle == 0L) { state("Czekam na model Whisper…", true); delay(700) }
                while (isActive) {
                    state("Słucham… powiedz „JasSI” i polecenie.", false)
                    val audio = try { Recorder.record(Int.MAX_VALUE) } catch (e: IllegalStateException) { delay(2500); continue }
                    if (audio.isEmpty()) continue
                    val text = transcribe(audio)
                    if (text.isBlank() || looksLikeNoise(text)) continue
                    val cmd = stripWake(text) ?: continue               // bez „JasSI” – ignorujemy rozmowy w tle
                    if (cmd.isBlank()) {
                        state("Słucham polecenia…", true)
                        tts.speak("Słucham.")
                        val a2 = Recorder.record(80)
                        if (a2.isEmpty()) continue
                        val t2 = transcribe(a2)
                        if (t2.isBlank() || looksLikeNoise(t2)) continue
                        processText(t2)
                    } else {
                        processText(cmd)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Błąd", e.message ?: e.toString())
            } finally {
                state(readyText(), false)
            }
        }
    }

    fun stopContinuous() { contJob?.cancel(); contJob = null }
}
