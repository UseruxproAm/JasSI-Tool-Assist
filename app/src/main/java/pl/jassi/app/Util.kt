package pl.jassi.app

import kotlin.math.max
import kotlin.math.min

object Util {
    private const val FROM = "ąćęłńóśźż"
    private const val TO = "acelnoszz"

    /** Zachowuje długość tekstu: małe litery, bez polskich ogonków (indeksy pasują do oryginału). */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.lowercaseChar()
            val i = FROM.indexOf(c)
            sb.append(if (i >= 0) TO[i] else c)
        }
        return sb.toString()
    }

    fun norm(s: String): String = fold(s)
        .replace(Regex("[,;:!?\"'()]"), " ")
        .replace(Regex("\\.+(\\s|$)"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    fun sim(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 1.0 - lev(a, b).toDouble() / max(a.length, b.length)
    }

    /** Podobieństwo pojedynczych słów z uwzględnieniem polskiej odmiany (mamy~mama, Kowalskiego~Kowalski). */
    fun wordSim(a: String, b: String): Double {
        if (a == b) return 1.0
        val k = min(a.length, b.length) - 1
        if (k >= 3 && a.take(k) == b.take(k)) return 0.95
        return sim(a, b)
    }

    private val UNITS = mapOf("zero" to 0, "jeden" to 1, "jedna" to 1, "jedno" to 1, "dwa" to 2, "dwie" to 2, "trzy" to 3,
        "cztery" to 4, "piec" to 5, "szesc" to 6, "siedem" to 7, "osiem" to 8, "dziewiec" to 9)
    private val TEENS = mapOf("dziesiec" to 10, "jedenascie" to 11, "dwanascie" to 12, "trzynascie" to 13, "czternascie" to 14,
        "pietnascie" to 15, "szesnascie" to 16, "siedemnascie" to 17, "osiemnascie" to 18, "dziewietnascie" to 19)
    private val TENS = mapOf("dwadziescia" to 20, "trzydziesci" to 30, "czterdziesci" to 40, "piecdziesiat" to 50, "szescdziesiat" to 60)

    /** (wartość, ile tokenów zużyto) – cyfry albo polskie słowa (0–60). */
    fun numberAt(tokens: List<String>, i: Int): Pair<Int, Int>? {
        val w = tokens.getOrNull(i) ?: return null
        w.toIntOrNull()?.let { return Pair(it, 1) }
        TEENS[w]?.let { return Pair(it, 1) }
        TENS[w]?.let { t ->
            val u = UNITS[tokens.getOrNull(i + 1) ?: ""]
            return if (u != null && u > 0) Pair(t + u, 2) else Pair(t, 1)
        }
        UNITS[w]?.let { return Pair(it, 1) }
        return null
    }
}
