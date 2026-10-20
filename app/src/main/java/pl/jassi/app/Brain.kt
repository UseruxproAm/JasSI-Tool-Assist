package pl.jassi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jassi.llama.Llama

/** Gemma 3 1B – rozmowa. Gemma nie ma roli "system", więc instrukcja idzie w pierwszą turę użytkownika. */
class Brain {
    private val history = ArrayDeque<Pair<String, String>>()

    private val system =
        "Jesteś JasSI, osobisty asystent głosowy w telefonie. Odpowiadaj zawsze po polsku, " +
        "zwięźle (1-3 krótkie zdania), naturalnym językiem mówionym, bez markdown, list i emoji. " +
        "Nigdy nie udawaj, że wykonałeś akcję na telefonie (otwarcie aplikacji, telefon, SMS) – to robi osobny moduł. " +
        "Jeśli polecenie jest niejasne, poproś o powtórzenie."

    suspend fun reply(user: String): String = withContext(Dispatchers.Default) {
        val turns = history.toList() + (user to null as String?)
        val sb = StringBuilder()
        turns.forEachIndexed { i, (u, a) ->
            sb.append("<start_of_turn>user\n")
            if (i == 0) sb.append(system).append("\n\n")
            sb.append(u).append("<end_of_turn>\n<start_of_turn>model\n")
            if (a != null) sb.append(a).append("<end_of_turn>\n")
        }
        var out = Llama.generate(sb.toString(), 200, 4).trim()
        out = out.replace(Regex("[*_#`]"), "")
        if (out.isBlank()) out = "Przepraszam, nie umiem teraz odpowiedzieć."
        history.addLast(user to out)
        while (history.size > 3) history.removeFirst()
        out
    }
}
