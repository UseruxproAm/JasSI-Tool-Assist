package pl.jassi.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

/**
 * "Ręce" asystenta: klika, przewija, wpisuje tekst, wykonuje gesty i globalne akcje (wstecz, home, powiadomienia…).
 * Użytkownik włącza ją RAZ w Ustawienia → Dostępność → JasSI. Potem działa bez dotykania ekranu.
 */
class JasSIService : AccessibilityService() {
    companion object {
        @Volatile var instance: JasSIService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        out.add(n)
        if (depth > 40) return
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out, depth + 1)
        }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val list = ArrayList<AccessibilityNodeInfo>()
        collect(root, list)
        return list
    }

    // ---------- klikanie po tekście ----------
    /** Zwraca etykietę klikniętego elementu albo null. */
    fun clickText(query: String): String? {
        val q = Util.norm(query)
        if (q.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        var bestLabel = ""
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val labels = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            for (lbl in labels) {
                val l = Util.norm(lbl)
                if (l.isEmpty() || l.length > 80) continue
                var s = Util.sim(q, l)
                if (l.contains(q)) s = max(s, 0.9 - 0.005 * (l.length - q.length).coerceAtMost(40))
                else if (q.contains(l) && l.length >= 3) s = max(s, 0.75)
                if (n.isClickable) s += 0.02
                if (s > bestScore) { bestScore = s; best = n; bestLabel = lbl }
            }
        }
        val target = best ?: return null
        if (bestScore < 0.7) return null
        return if (clickNode(target)) bestLabel else null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 7) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            d++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    // ---------- gesty ----------
    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        return dispatchGesture(g, null, null)
    }

    fun scroll(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        if (dir == "dol" || dir == "gora") {
            val sc = allNodes().filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull {
                val r = Rect(); it.getBoundsInScreen(r); r.width().toLong() * r.height()
            }
            val act = if (dir == "dol") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (sc != null && sc.performAction(act)) return true
        }
        return when (dir) {
            "dol" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "gora" -> swipe(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "lewo" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            "prawo" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
            else -> false
        }
    }

    // ---------- tekst ----------
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            target = allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
        }
        val t = target ?: return false
        t.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (Build.VERSION.SDK_INT >= 30 && f != null &&
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        for (l in listOf("szukaj", "search", "wyslij", "send", "gotowe", "dalej")) {
            if (clickText(l) != null) return true
        }
        return false
    }

    fun readScreen(max: Int = 25): String {
        val seen = LinkedHashSet<String>()
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()
            if (!t.isNullOrBlank() && t.length < 200) seen.add(t)
            if (seen.size >= max) break
        }
        return seen.joinToString(". ")
    }
}
