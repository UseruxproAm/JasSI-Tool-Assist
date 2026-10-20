package pl.jassi.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * "Ręce i nogi" JasSI. Rozpoznaje polskie polecenia i wykonuje je na telefonie:
 * aplikacje (wyszukiwanie rozmyte + aliasy + Sklep Play), strony, kontakty (z dopytaniem gdy kilku pasuje),
 * połączenia, SMS, WhatsApp, nawigacja, budzik/minutnik, głośność, multimedia, ustawienia, oraz sterowanie
 * ekranem przez usługę dostępności (klik, przewijanie, wpisywanie, wstecz, home, powiadomienia, zrzut ekranu…).
 * handle() zwraca tekst do powiedzenia albo null, jeśli to nie polecenie (wtedy odpowiada Gemma).
 */
class Actions(private val ctx: Context) {

    @Volatile var awaitingReply = false
        private set
    private var pending: (suspend (String) -> String)? = null
    private val svc get() = JasSIService.instance

    fun cancelPending() { pending = null; awaitingReply = false }

    private fun ask(question: String, onReply: suspend (String) -> String): String {
        pending = onReply
        awaitingReply = true
        return question
    }

    // =====================================================================================
    suspend fun handle(raw: String): String? {
        pending?.let { p -> cancelPending(); return p(raw) }

        val f = Util.fold(raw)
        val t = clean(Util.norm(raw))
        if (t.isEmpty()) return null

        // ---------- sterowanie telefonem (dostępność) ----------
        if (t in BACK) return glob(AccessibilityService.GLOBAL_ACTION_BACK, "Wracam.")
        if (t in HOME) return glob(AccessibilityService.GLOBAL_ACTION_HOME, "Ekran główny.")
        if (t in RECENTS) return glob(AccessibilityService.GLOBAL_ACTION_RECENTS, "Ostatnie aplikacje.")
        if (t.contains("szybkie ustawienia") || t.contains("panel szybkich")) return glob(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Szybkie ustawienia.")
        if (t.contains("powiadomienia") && Regex("""^(otworz|pokaz|rozwin|sprawdz|zobacz)""").containsMatchIn(t))
            return glob(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Powiadomienia.")
        if (Regex("""^(zablokuj|wylacz|uspij)\s+(telefon|ekran)""").containsMatchIn(t))
            return if (Build.VERSION.SDK_INT >= 28) glob(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Blokuję ekran.") else "To wymaga Androida 9 lub nowszego."
        if (t.contains("zrzut ekranu") || t.contains("screenshot") || t.contains("screena"))
            return if (Build.VERSION.SDK_INT >= 30) glob(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Robię zrzut ekranu.") else "To wymaga Androida 11 lub nowszego."

        if (t.contains("co jest na ekranie") || t.contains("przeczytaj ekran") || t == "co widze" || t.contains("co sie wyswietla")) {
            val s = svc ?: return needA11y()
            val txt = s.readScreen()
            return if (txt.isBlank()) "Nie widzę tekstu na ekranie." else txt.take(450)
        }

        Regex("""^(?:przewin|scrolluj|przesun)\s+(?:w\s+|na\s+|do\s+)?(dol|gore|gory|nizej|wyzej|lewo|prawo)""").find(t)?.let { m ->
            val s = svc ?: return needA11y()
            val dir = when (m.groupValues[1]) { "dol", "nizej" -> "dol"; "gore", "gory", "wyzej" -> "gora"; else -> m.groupValues[1] }
            return if (s.scroll(dir)) "Przewijam." else "Nie mogę przewinąć."
        }

        Regex("""^(?:kliknij|nacisnij|dotknij|wybierz|stuknij|tapnij|zaznacz)(?:\s+w|\s+na)?\s+(.+)$""").find(t)?.let { m ->
            val s = svc ?: return needA11y()
            val target = m.groupValues[1]
            val hit = s.clickText(target)
            return if (hit != null) "Klikam $hit." else "Nie widzę „$target” na ekranie."
        }

        rawGroup(raw, f, Regex("""(?:^|\s)(?:wpisz|podyktuj|wklep)\s+(.+)$"""))?.let { txt ->
            val s = svc ?: return needA11y()
            return if (s.typeText(txt.trimEnd('.'))) "Wpisałem." else "Nie widzę pola tekstowego."
        }

        if (t in setOf("zatwierdz", "enter", "potwierdz", "szukaj", "wyslij to")) {
            val s = svc ?: return needA11y()
            return if (s.pressEnter()) "Zatwierdzone." else "Nie wiem, co zatwierdzić."
        }

        // ---------- połączenia ----------
        if (t in setOf("odbierz", "odbierz telefon", "odbierz polaczenie")) return answerCall()
        if (t in setOf("rozlacz", "rozlacz sie", "zakoncz rozmowe", "odrzuc", "odrzuc polaczenie", "odloz sluchawke")) return endCall()

        // ---------- głośność / multimedia ----------
        volume(t)?.let { return it }
        mediaControl(t)?.let { return it }

        // ---------- sprzęt / info ----------
        if (t.contains("latark")) return torch(!(t.contains("wylacz") || t.contains("zgas")))
        if (t.contains("ktora godzina") || t.contains("jaka jest godzina") || t.contains("ktora jest"))
            return "Jest ${SimpleDateFormat("H:mm", Locale("pl")).format(Date())}."
        if (t.contains("jaka jest data") || t.contains("jaki jest dzis") || t.contains("jaki mamy dzien") || t.contains("ktory dzisiaj") || t.contains("jaki dzien"))
            return "Dziś jest ${SimpleDateFormat("EEEE, d MMMM", Locale("pl")).format(Date())}."
        if (t.contains("baterii") || t.contains("bateria") || t.contains("naladowanie")) {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            return "Bateria ma ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} procent."
        }

        // ---------- przełączniki (szybkie ustawienia) ----------
        Regex("""^(wlacz|wylacz|przelacz|zmien)\s+(wifi|wi fi|wi-fi|bluetooth|tryb samolotowy|tryb lotniczy|dane mobilne|transmisje danych|internet mobilny)""").find(t)?.let { m ->
            val what = m.groupValues[2]
            val labels = when {
                what.startsWith("wi") -> listOf("Wi-Fi", "Internet")
                what == "bluetooth" -> listOf("Bluetooth")
                what.startsWith("tryb") -> listOf("Tryb samolotowy", "Tryb lotniczy", "Samolot", "Airplane mode")
                else -> listOf("Dane mobilne", "Mobile data", "Internet")
            }
            return toggleTile(what, labels)
        }

        // ---------- budzik / minutnik ----------
        timerOrAlarm(t)?.let { return it }

        // ---------- WhatsApp / SMS ----------
        val waRx = Regex("""(?:na|przez|w)\s+(?:whatsapp|watsap|whatsap|wotsap|łatsap|latsap)\w*""")
        val msgRx = Regex("""(?:wyslij|napisz)\s+(?:(?:sms|smsa|esemes|esemesa|wiadomosc|wiadomosci)\s+)?(?:do\s+)?(.+?)\s+(?:ze|o tresci|tresc|tresci)\s+(.+)$""")
        run {
            var r = raw; var ff = f
            val wa = waRx.find(ff)
            val isWa = wa != null
            if (wa != null) {
                val rg = wa.range
                r = r.removeRange(rg.first, rg.last + 1); ff = ff.removeRange(rg.first, rg.last + 1)
            }
            val m = msgRx.find(ff)
            if (m != null && (isWa || ff.contains("sms") || ff.contains("esemes") || ff.contains("wiadomosc") || ff.contains("napisz") || ff.contains("wyslij"))) {
                val who = rawGroup(r, ff, msgRx, 1)?.trim(' ', ',', '.') ?: return@run
                val msg = rawGroup(r, ff, msgRx, 2) ?: return@run
                return if (isWa) whatsapp(who, msg) else sms(who, msg)
            }
        }

        // ---------- dzwonienie ----------
        Regex("""^(?:zadzwon|dzwon|zatelefonuj|polacz sie|wykonaj polaczenie)(?:\s+do|\s+z|\s+na numer|\s+na)?\s+(.+)$""").find(t)?.let { m ->
            return call(m.groupValues[1])
        }

        // ---------- kontakty ----------
        Regex("""^(?:znajdz|pokaz|wyszukaj|sprawdz|podaj)\s+(?:kontakt|numer)(?:\s+do|\s+osoby)?\s+(.+)$""").find(t)?.let { m ->
            return withContact(m.groupValues[1]) { c -> "Kontakt ${c.name}, numer ${c.number}." }
        }

        // ---------- nawigacja ----------
        rawGroup(raw, f, Regex("""(?:nawiguj|prowadz|jedz|zaprowadz|wyznacz trase|jak dojechac|jak dojsc)\s+(?:mnie\s+)?(?:do|na)\s+(.+)$"""))?.let { dest ->
            val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))))
            return if (ok) "Nawiguję do $dest." else "Nie mogę uruchomić nawigacji."
        }
        rawGroup(raw, f, Regex("""(?:pokaz|znajdz|wyszukaj)\s+(.+?)\s+na\s+mapie$"""))?.let { place ->
            val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
            return if (ok) "Pokazuję $place na mapie." else "Nie mogę otworzyć map."
        }

        // ---------- muzyka / wideo ----------
        rawGroup(raw, f, Regex("""^(?:.*?\s)?(?:pusc|zagraj|odtworz|odtwarzaj)\s+(.+)$"""))?.let { q ->
            return play(q)
        }
        rawGroup(raw, f, Regex("""(?:znajdz|wyszukaj|szukaj|pokaz)\s+(.+?)\s+na\s+youtube\w*$"""))?.let { q ->
            val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
            return if (ok) "Szukam $q na YouTube." else "Nie mogę otworzyć YouTube."
        }

        // ---------- aplikacje / strony / ustawienia ----------
        Regex("""^(?:znajdz|wyszukaj|poszukaj)\s+(?:aplikacje|aplikacja|appke|program)\s+(.+)$""").find(t)?.let { m ->
            return openApp(m.groupValues[1], explicit = true)
        }
        Regex("""^(otworz|uruchom|odpal|wlacz|wejdz na|wejdz do|wejdz w|idz na|idz do|przejdz do|przejdz na|pokaz)\s+(.+)$""").find(t)?.let { m ->
            return openTarget(m.groupValues[1], m.groupValues[2])
        }

        // ---------- wyszukiwanie w internecie ----------
        rawGroup(raw, f, Regex("""(?:^|\s)(?:wyszukaj|poszukaj|znajdz|wygoogluj|szukaj|sprawdz)\s+(?:w\s+(?:google|internecie|necie)\s+)?(.+)$"""))?.let { q ->
            val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            return if (ok) "Szukam: $q." else "Nie mogę wyszukać."
        }

        return null
    }

    // =====================================================================================
    // pomocnicze: czyszczenie komendy
    private val FILL = setOf("jasi", "jassi", "jasiu", "hej", "hey", "ok", "okej", "mozesz", "moglbys", "moglabys", "czy", "chce",
        "chcialbym", "chcialabym", "zebys", "teraz", "a", "no", "to", "wiec", "dobra", "sluchaj", "dzieki", "jeszcze", "i")
    private val INF = mapOf("otworzyc" to "otworz", "uruchomic" to "uruchom", "wlaczyc" to "wlacz", "wylaczyc" to "wylacz",
        "zadzwonic" to "zadzwon", "wyslac" to "wyslij", "napisac" to "napisz", "przewinac" to "przewin", "kliknac" to "kliknij",
        "znalezc" to "znajdz", "wyszukac" to "wyszukaj", "pokazac" to "pokaz", "puscic" to "pusc", "zagrac" to "zagraj",
        "cofnac" to "cofnij", "odpalic" to "odpal", "ustawic" to "ustaw", "przejsc" to "przejdz", "wejsc" to "wejdz")

    private fun clean(t: String): String {
        val w = t.split(" ").filter { it.isNotEmpty() && it != "mi" && it != "prosze" }.toMutableList()
        while (w.isNotEmpty() && w[0] in FILL) w.removeAt(0)
        if (w.isNotEmpty()) INF[w[0]]?.let { w[0] = it }
        return w.joinToString(" ")
    }

    /** Wycina grupę z ORYGINALNEGO tekstu (z polskimi znakami), używając indeksów z fold() – długość się zgadza. */
    private fun rawGroup(raw: String, f: String, rx: Regex, g: Int = 1): String? {
        if (raw.length != f.length) return null
        val m = rx.find(f) ?: return null
        val r = m.groups[g]?.range ?: return null
        val s = raw.substring(r.first, r.last + 1).trim().trim('.', ',', '!', '?', ' ')
        return s.ifBlank { null }
    }

    private val BACK = setOf("wroc", "cofnij", "cofnij sie", "wstecz", "przycisk wstecz", "wroc do poprzedniego ekranu")
    private val HOME = setOf("ekran glowny", "pulpit", "home", "idz do domu", "wroc do ekranu glownego", "przejdz do ekranu glownego",
        "wyjdz z aplikacji", "zamknij aplikacje", "zamknij")
    private val RECENTS = setOf("ostatnie aplikacje", "ostatnio uzywane", "przelacz aplikacje", "otworz ostatnie aplikacje",
        "pokaz ostatnie aplikacje", "multitasking")

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun needA11y(): String {
        startActivitySafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return "Muszę mieć włączoną usługę dostępności JasSI. Otwieram ustawienia – włącz ją raz, a potem wszystko zadziała samo."
    }

    private fun glob(action: Int, ok: String): String {
        val s = svc ?: return needA11y()
        return if (s.performGlobalAction(action)) ok else "Nie udało się wykonać tej czynności."
    }

    private fun startActivitySafe(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false } catch (e: Exception) { false }

    // =====================================================================================
    // otwieranie: aplikacje, strony, ustawienia
    private val browserWords = setOf("przegladarke", "przegladarka", "chrome", "internet", "firefoxa", "firefox", "opere", "browser")

    private suspend fun openTarget(verb: String, restRaw: String): String {
        var rest = restRaw
        // ustawienia podrzędne
        if (rest.startsWith("ustawienia ") || rest.startsWith("ustawien ")) {
            val sub = rest.substringAfter(" ")
            settingsMap.firstOrNull { (keys, _) -> keys.any { sub.contains(it) } }?.let { (_, action) ->
                return if (startActivitySafe(Intent(action))) "Otwieram ustawienia." else "Nie mogę otworzyć tych ustawień."
            }
        }
        if (rest in browserWords || rest.startsWith("przegladark")) return openBrowser()
        Regex("""^(?:strone|witryne|stronke|adres|www)\s+(.+)$""").find(rest)?.let { return website(it.groupValues[1]) }
        val cleanedDomain = rest.replace(" kropka ", ".")
        if (Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$""").matches(cleanedDomain)) return website(cleanedDomain)

        var explicit = false
        Regex("""^(?:aplikacje|aplikacja|aplikacji|appke|program)\s+(.+)$""").find(rest)?.let { rest = it.groupValues[1]; explicit = true }
        return openApp(rest, explicit, fallbackToWeb = !explicit)
    }

    private fun openBrowser(): String {
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        return if (startActivitySafe(i)) "Otwieram przeglądarkę." else "Nie znalazłem przeglądarki."
    }

    private fun website(target: String): String {
        val q = target.replace(" kropka ", ".").trim()
        val compact = q.replace(" ", "")
        val hasDot = Regex("""^(https?://)?[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$""").matches(compact)
        val url = if (hasDot) { if (compact.startsWith("http")) compact else "https://$compact" }
        else "https://www.google.com/search?btnI=1&q=" + Uri.encode(target)   // pierwszy wynik Google
        return if (startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Otwieram stronę." else "Nie mogę otworzyć strony."
    }

    private val settingsMap: List<Pair<List<String>, String>> = listOf(
        listOf("wifi", "wi fi", "wi-fi") to Settings.ACTION_WIFI_SETTINGS,
        listOf("bluetooth") to Settings.ACTION_BLUETOOTH_SETTINGS,
        listOf("dzwiek", "glosnosc") to Settings.ACTION_SOUND_SETTINGS,
        listOf("ekran", "wyswietlacz", "jasnosc") to Settings.ACTION_DISPLAY_SETTINGS,
        listOf("bateria", "oszczedzanie") to Intent.ACTION_POWER_USAGE_SUMMARY,
        listOf("aplikacje") to Settings.ACTION_APPLICATION_SETTINGS,
        listOf("lokalizacja", "gps") to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        listOf("dane mobilne", "siec komorkowa", "roaming") to Settings.ACTION_DATA_ROAMING_SETTINGS,
        listOf("tryb samolotowy", "samolot") to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        listOf("dostepnosc") to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        listOf("pamiec", "miejsce") to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        listOf("jezyk", "jezyki") to Settings.ACTION_LOCALE_SETTINGS,
        listOf("data", "godzina", "czas") to Settings.ACTION_DATE_SETTINGS,
        listOf("nfc") to Settings.ACTION_NFC_SETTINGS
    )

    private class AppHit(val label: String, val pkg: String)

    private fun installedApps(): List<AppHit> {
        val pm = ctx.packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(li, 0)
            .map { AppHit(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
    }

    private fun a(keys: List<String>, vararg pkgs: String) = Pair(keys, pkgs.toList())
    private val aliases: List<Pair<List<String>, List<String>>> = listOf(
        a(listOf("youtube", "jutub", "jutjub", "yt"), "com.google.android.youtube"),
        a(listOf("facebook", "fejsbuk", "fejs"), "com.facebook.katana", "com.facebook.lite"),
        a(listOf("messenger", "mesendzer", "mesenger"), "com.facebook.orca"),
        a(listOf("whatsapp", "watsap", "whatsap", "wotsap", "latsap", "watsapp"), "com.whatsapp", "com.whatsapp.w4b"),
        a(listOf("instagram", "insta"), "com.instagram.android"),
        a(listOf("tiktok", "tik tok", "tiktok"), "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        a(listOf("spotify", "spotifaj", "spotyfaj"), "com.spotify.music"),
        a(listOf("netflix", "netfliks"), "com.netflix.mediaclient"),
        a(listOf("gmail", "dzimejl", "jimejl", "dzimeil"), "com.google.android.gm"),
        a(listOf("chrome", "krom"), "com.android.chrome"),
        a(listOf("telegram"), "org.telegram.messenger"),
        a(listOf("snapchat", "snap"), "com.snapchat.android"),
        a(listOf("discord", "dyskord"), "com.discord"),
        a(listOf("twitter", "tweeter"), "com.twitter.android"),
        a(listOf("linkedin"), "com.linkedin.android"),
        a(listOf("zoom"), "us.zoom.videomeetings"),
        a(listOf("teams"), "com.microsoft.teams"),
        a(listOf("outlook"), "com.microsoft.office.outlook"),
        a(listOf("uber"), "com.ubercab"),
        a(listOf("bolt"), "ee.mtakso.client"),
        a(listOf("revolut"), "com.revolut.revolut"),
        a(listOf("signal"), "org.thoughtcrime.securesms"),
        a(listOf("viber", "wajber"), "com.viber.voip"),
        a(listOf("skype", "skajp"), "com.skype.raider"),
        a(listOf("reddit"), "com.reddit.frontpage"),
        a(listOf("pinterest"), "com.pinterest"),
        a(listOf("twitch"), "tv.twitch.android.app"),
        a(listOf("allegro"), "pl.allegro"),
        a(listOf("olx"), "pl.tablica"),
        a(listOf("paypal"), "com.paypal.android.p2pmobile"),
        a(listOf("dysk", "google drive", "drive"), "com.google.android.apps.docs"),
        a(listOf("tlumacz", "translate"), "com.google.android.apps.translate"),
        a(listOf("meet"), "com.google.android.apps.tachyon"),
        a(listOf("zdjecia google", "google photos"), "com.google.android.apps.photos")
    )

    private fun st(nq: String, vararg stems: String) = stems.any { nq == it || nq.startsWith(it) }

    private fun systemIntent(nq: String): Pair<String, Intent>? = when {
        st(nq, "ustawienia", "ustawien") -> "Ustawienia" to Intent(Settings.ACTION_SETTINGS)
        st(nq, "aparat", "kamer") -> "Aparat" to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        st(nq, "kalkulator") -> "Kalkulator" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        st(nq, "kontakt") -> "Kontakty" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
        st(nq, "wiadomosc", "wiadomosci", "smsy", "sms") -> "Wiadomości" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        st(nq, "kalendarz") -> "Kalendarz" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        st(nq, "mapy", "mape", "mapa", "google maps") -> "Mapy" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
        st(nq, "muzyk") -> "Muzyka" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        st(nq, "galeri") -> "Galeria" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        st(nq, "poczt", "email", "e-mail") -> "Poczta" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)
        st(nq, "telefon", "dialer", "klawiatur") -> "Telefon" to Intent(Intent.ACTION_DIAL)
        st(nq, "zegar", "budzik", "budziki") -> "Zegar" to Intent(AlarmClock.ACTION_SHOW_ALARMS)
        st(nq, "sklep play", "google play", "play store", "sklep") -> "Sklep Play" to Intent(Intent.ACTION_VIEW, Uri.parse("market://"))
        else -> null
    }

    private fun score(q: String, l: String): Double {
        if (l == q) return 1.0
        var s = Util.sim(q, l)
        if (l.contains(q) && q.length >= 3) s = max(s, 0.88 - 0.01 * (l.length - q.length).coerceAtMost(20))
        else if (q.contains(l) && l.length >= 3) s = max(s, 0.8)
        val qs = q.split(" ").filter { it.isNotBlank() }.map { it.take(max(3, it.length - 2)) }
        if (qs.isNotEmpty() && qs.all { l.contains(it) }) s = max(s, 0.8 - 0.005 * l.length)
        val lw = l.split(" ")
        val qw = q.split(" ")
        val avg = qw.map { w -> lw.maxOf { Util.wordSim(w, it) } }.average()
        return max(s, avg * 0.95)
    }

    private fun openApp(nameRaw: String, explicit: Boolean, fallbackToWeb: Boolean = false): String {
        val nq = Util.norm(nameRaw)
        val pm = ctx.packageManager

        // 1. aplikacje systemowe (niezależne od producenta)
        systemIntent(nq)?.let { (label, intent) ->
            if (startActivitySafe(intent)) return "Otwieram $label."
        }
        // 2. aliasy popularnych aplikacji (wymowa po polsku)
        for ((keys, pkgs) in aliases) {
            if (keys.any { nq == it || nq.startsWith("$it ") || (it.length >= 4 && nq.startsWith(it)) }) {
                for (p in pkgs) {
                    val li = pm.getLaunchIntentForPackage(p) ?: continue
                    if (startActivitySafe(li)) return "Otwieram ${keys.first().replaceFirstChar { c -> c.uppercase() }}."
                }
                // nie zainstalowana -> Sklep Play
                val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${pkgs.first()}")))
                return if (ok) "Nie masz aplikacji ${keys.first()}. Otwieram Sklep Play." else "Nie masz aplikacji ${keys.first()}."
            }
        }
        // 3. rozmyte dopasowanie do zainstalowanych
        val ranked = installedApps().map { it to score(nq, Util.norm(it.label)) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull()
        val threshold = if (explicit) 0.6 else 0.72
        if (best != null && best.second >= threshold) {
            val li = pm.getLaunchIntentForPackage(best.first.pkg)
            if (li != null && startActivitySafe(li)) return "Otwieram ${best.first.label}."
        }
        // 4. nie ma -> strona w przeglądarce albo Sklep Play
        if (fallbackToWeb) return "Nie mam aplikacji $nameRaw, więc szukam w internecie. " + website(nameRaw)
        val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(nameRaw) + "&c=apps")))
        return if (ok) "Nie znalazłem aplikacji $nameRaw. Szukam w Sklepie Play." else "Nie znalazłem aplikacji $nameRaw."
    }

    // =====================================================================================
    // kontakty, połączenia, SMS, WhatsApp
    private class Contact(val name: String, val number: String)

    private fun loadContacts(): List<Contact> {
        if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        val P = ContactsContract.CommonDataKinds.Phone
        val map = LinkedHashMap<String, Pair<String, Int>>()
        ctx.contentResolver.query(P.CONTENT_URI, arrayOf(P.DISPLAY_NAME, P.NUMBER, P.TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val type = c.getInt(2)
                val prev = map[n]
                if (prev == null || (type == P.TYPE_MOBILE && prev.second != P.TYPE_MOBILE)) map[n] = Pair(num, type)
            }
        }
        return map.map { Contact(it.key, it.value.first) }
    }

    private fun rank(list: List<Contact>, q: String): List<Pair<Contact, Double>> {
        val qw = Util.norm(q).split(" ").filter { it.isNotBlank() }
        if (qw.isEmpty()) return emptyList()
        return list.mapNotNull { c ->
            val nw = Util.norm(c.name).split(" ").filter { it.isNotBlank() }
            if (nw.isEmpty()) return@mapNotNull null
            val s = qw.map { w -> nw.maxOf { Util.wordSim(w, it) } }.average() + if (nw.size == qw.size) 0.04 else 0.0
            if (s >= 0.72) Pair(c, s) else null
        }.sortedByDescending { it.second }
    }

    private suspend fun withContact(query: String, action: suspend (Contact) -> String): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return "Nie mam dostępu do kontaktów. Zezwól w ustawieniach aplikacji."
        val ranked = rank(loadContacts(), query)
        if (ranked.isEmpty()) return "Nie znalazłem kontaktu $query."
        val top = ranked[0]
        val close = ranked.filter { it.second >= top.second - 0.06 }.take(3)
        if (close.size == 1) return action(top.first)
        val names = close.joinToString(" albo ") { it.first.name }
        return ask("Mam kilku: $names. Którego wybrać?") { reply ->
            val r = rank(close.map { it.first }, reply)
            if (r.isEmpty()) "Nie zrozumiałem. Anuluję." else action(r[0].first)
        }
    }

    private suspend fun call(target: String): String {
        val digits = target.filter { it.isDigit() }
        if (digits.length >= 3 && digits.length >= target.replace(" ", "").length - 2) return dial(digits, digits.chunked(3).joinToString(" "))
        return withContact(target) { c -> dial(c.number, c.name) }
    }

    private fun dial(number: String, who: String): String {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (granted(Manifest.permission.CALL_PHONE)) {
            if (startActivitySafe(Intent(Intent.ACTION_CALL, uri))) "Dzwonię do $who." else "Nie mogę wykonać połączenia."
        } else {
            startActivitySafe(Intent(Intent.ACTION_DIAL, uri))
            "Nie mam zgody na automatyczne dzwonienie. Otwieram wybieranie numeru $who."
        }
    }

    @SuppressLint("MissingPermission")
    private fun answerCall(): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return "Nie mam zgody na odbieranie połączeń."
        return try { ctx.getSystemService(TelecomManager::class.java).acceptRingingCall(); "Odbieram." } catch (e: Exception) { "Nie mogę odebrać." }
    }

    @SuppressLint("MissingPermission")
    private fun endCall(): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return "Nie mam zgody na kończenie połączeń."
        return try {
            if (Build.VERSION.SDK_INT >= 28 && ctx.getSystemService(TelecomManager::class.java).endCall()) "Rozłączam." else "Nie ma aktywnego połączenia."
        } catch (e: Exception) { "Nie mogę rozłączyć." }
    }

    private suspend fun sms(who: String, msg: String): String {
        if (!granted(Manifest.permission.SEND_SMS)) return "Nie mam zgody na wysyłanie SMS."
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 6) return sendSms(digits, digits, msg)
        return withContact(who) { c -> sendSms(c.number, c.name, msg) }
    }

    private fun sendSms(number: String, who: String, msg: String): String = try {
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(msg), null, null)
        "Wysłałem SMS do $who: $msg"
    } catch (e: Exception) { "Nie udało się wysłać SMS." }

    private suspend fun whatsapp(who: String, msg: String): String {
        val digits = who.filter { it.isDigit() }
        suspend fun go(number: String, name: String): String {
            var n = number.filter { it.isDigit() }
            if (n.startsWith("00")) n = n.drop(2)
            if (n.length == 9) n = "48$n"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(msg)))
            if (ctx.packageManager.getLaunchIntentForPackage("com.whatsapp") != null) i.setPackage("com.whatsapp")
            if (!startActivitySafe(i)) return "Nie mogę otworzyć WhatsAppa."
            val s = svc ?: return "Otworzyłem czat z $name, ale muszę mieć włączoną dostępność, żeby kliknąć wyślij."
            delay(2800)
            val hit = s.clickText("Wyślij") ?: s.clickText("Send")
            return if (hit != null) "Wysłałem wiadomość do $name." else "Otworzyłem czat z $name, ale nie widzę przycisku wyślij."
        }
        if (digits.length >= 6) return go(digits, digits)
        return withContact(who) { c -> go(c.number, c.name) }
    }

    // =====================================================================================
    // głośność, multimedia, budzik, sprzęt
    private fun volume(t: String): String? {
        val am = ctx.getSystemService(AudioManager::class.java)
        val ui = AudioManager.FLAG_SHOW_UI
        return when {
            t == "glosniej" || t.contains("zglosni") || t.contains("podglosni") -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, ui); "Głośniej." }
            t == "ciszej" || t.contains("sciszy") || t.contains("zciszy") -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, ui); "Ciszej." }
            t == "wycisz" || t.startsWith("wycisz ") -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, ui); "Wyciszone." }
            t.contains("glosnosc") && (t.contains(" na ") || t.contains("ustaw")) -> {
                val tokens = t.split(" ")
                var p: Int? = null
                for (i in tokens.indices) { val n = Util.numberAt(tokens, i); if (n != null) { p = n.first; break } }
                if (t.contains("maksimum") || t.contains("maksymaln")) p = 100
                val pct = p ?: return null
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct.coerceIn(0, 100) / 100), ui)
                "Głośność $pct procent."
            }
            else -> null
        }
    }

    private fun mediaKey(code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun mediaControl(t: String): String? = when {
        Regex("""^(wstrzymaj|zatrzymaj|pauza|pauzuj|zapauzuj)(\s+(muzyke|film|odtwarzanie|utwor))?$""").matches(t) -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); "Pauza." }
        Regex("""^(wznow|graj dalej|kontynuuj|wznow odtwarzanie|odpauzuj)$""").matches(t) -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); "Wznawiam." }
        Regex("""^(nastepny|nastepna|nastepny utwor|nastepna piosenka|pomin|pomin utwor)$""").matches(t) -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); "Następny utwór." }
        Regex("""^(poprzedni|poprzednia|poprzedni utwor|poprzednia piosenka)$""").matches(t) -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Poprzedni utwór." }
        else -> null
    }

    private fun play(q: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (startActivitySafe(i)) return "Puszczam $q."
        val ok = startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        return if (ok) "Szukam $q na YouTube." else "Nie mogę puścić muzyki."
    }

    private fun timerOrAlarm(t: String): String? {
        val tokens = t.split(" ")
        if (Regex("""(minutnik|timer|odlicz|stoper|za \S+ (sekund|minut|godzin))""").containsMatchIn(t) && !t.contains("budzik")) {
            var total = 0; var any = false; var i = 0
            while (i < tokens.size) {
                val num = Util.numberAt(tokens, i)
                if (num != null) {
                    val unit = tokens.getOrNull(i + num.second) ?: ""
                    val mult = when { unit.startsWith("sek") -> 1; unit.startsWith("min") -> 60; unit.startsWith("godz") -> 3600; else -> 0 }
                    if (mult > 0) { total += num.first * mult; any = true; i += num.second + 1; continue }
                    i += num.second; continue
                }
                if (tokens[i] == "pol" && tokens.getOrNull(i + 1)?.startsWith("godz") == true) { total += 1800; any = true; i += 2; continue }
                i++
            }
            if (!any || total <= 0) return null
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, total).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "JasSI")
            val mins = total / 60
            val txt = if (total >= 3600) "${total / 3600} godz. ${(total % 3600) / 60} min" else if (mins > 0) "$mins min" else "$total sek"
            return if (startActivitySafe(intent)) "Minutnik ustawiony na $txt." else "Nie mogę ustawić minutnika."
        }
        if (t.contains("budzik") || Regex("""^(ustaw )?alarm""").containsMatchIn(t)) {
            val nums = ArrayList<Int>()
            var i = 0
            while (i < tokens.size) { val n = Util.numberAt(tokens, i); if (n != null) { nums.add(n.first); i += n.second } else i++ }
            if (nums.isEmpty()) return null
            var h = nums[0]
            val min = nums.getOrNull(1) ?: 0
            if ((t.contains("po poludniu") || t.contains("wieczor") || t.contains("popoludniu")) && h < 12) h += 12
            if (h > 23 || min > 59) return null
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "JasSI")
            return if (startActivitySafe(intent)) "Budzik ustawiony na $h:${min.toString().padStart(2, '0')}." else "Nie mogę ustawić budzika."
        }
        return null
    }

    private suspend fun toggleTile(spoken: String, labels: List<String>): String {
        val s = svc ?: return needA11y()
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        delay(900)
        var hit: String? = null
        for (l in labels) { hit = s.clickText(l); if (hit != null) break }
        delay(300)
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return if (hit != null) "Przełączyłem: $spoken." else "Nie znalazłem „$spoken” w szybkich ustawieniach."
    }

    private fun torch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "Ten telefon nie ma latarki."
            cm.setTorchMode(id, on)
            if (on) "Latarka włączona." else "Latarka wyłączona."
        } catch (e: Exception) {
            "Nie mogę obsłużyć latarki."
        }
    }
