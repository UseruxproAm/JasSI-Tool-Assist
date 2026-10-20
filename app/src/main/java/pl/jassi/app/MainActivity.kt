package pl.jassi.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity(), Engine.Ui {
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var orb: TextView
    private lateinit var listenBtn: Button
    private var pulse: ValueAnimator? = null
    private val cyan = Color.parseColor("#00E5FF")

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        buildUi()
        val p = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.launch(p.toTypedArray())

        Engine.ui = this
        logView.text = Engine.transcript.toString()
        status.text = Engine.lastState
        Engine.start()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
    }

    override fun onDestroy() {
        if (Engine.ui === this) Engine.ui = null
        super.onDestroy()
    }

    // ---------------- UI ----------------
    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; setOnClickListener { onClick() }
    }

    private fun addRow(root: LinearLayout, vararg buttons: Button) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in buttons) row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#05080C"))
            setPadding(dp(16), dp(36), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "JasSI"; textSize = 34f; setTextColor(cyan)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.3f
        })
        status = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#7FB8C4")); gravity = Gravity.CENTER }
        root.addView(status)
        info = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#5C7F88")); gravity = Gravity.CENTER }
        root.addView(info)

        orb = TextView(this).apply {
            text = "MÓW"; gravity = Gravity.CENTER; textSize = 20f; setTextColor(cyan)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#0A1A22")); setStroke(dp(3), cyan)
            }
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)).apply { topMargin = dp(16); bottomMargin = dp(16) }
            setOnClickListener { Engine.toggleTalk() }
        }
        root.addView(orb)

        logView = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#CFE9EF")) }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        listenBtn = btn("") { toggleListen() }
        addRow(root, listenBtn)
        addRow(root,
            btn("1. Włącz sterowanie telefonem") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("2. Działanie w tle (nad aplikacjami)") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
        addRow(root,
            btn("Pobierz modele") { Engine.downloadModels() },
            btn("Jakość Whisper") { pickWhisper() },
            btn("Bateria: bez limitów") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        setContentView(root)
    }

    private fun pickWhisper() {
        val keys = Engine.WHISPER_LABELS.keys.toList()
        val labels = Engine.WHISPER_LABELS.values.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Jakość rozpoznawania mowy (obecnie: ${Engine.whisperChoice()})")
            .setItems(labels) { _, i -> Engine.chooseWhisper(keys[i]) }
            .show()
    }

    private fun toggleListen() {
        if (Engine.continuous) {
            stopService(Intent(this, ListenService::class.java))
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Najpierw zezwól na mikrofon.", Toast.LENGTH_LONG).show()
                perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return
            }
            ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java))
        }
        orb.postDelayed({ refreshInfo() }, 500)
    }

    private fun refreshInfo() {
        val a11y = if (JasSIService.instance != null) "sterowanie telefonem: WŁĄCZONE" else "sterowanie telefonem: wyłączone (krok 1)"
        val overlay = if (Settings.canDrawOverlays(this)) "działanie w tle: OK" else "działanie w tle: brak zgody (krok 2)"
        info.text = "$a11y\n$overlay"
        listenBtn.text = if (Engine.continuous) "Tryb głośnomówiący: WŁĄCZONY (kliknij, aby wyłączyć)" else "Włącz tryb głośnomówiący – mów „JasSI …”"
    }

    // ---------------- Engine.Ui ----------------
    override fun onState(text: String, active: Boolean) {
        status.text = text
        if (active && pulse == null) {
            pulse = ValueAnimator.ofFloat(1f, 1.12f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { orb.scaleX = it.animatedValue as Float; orb.scaleY = it.animatedValue as Float }
                start()
            }
        } else if (!active) {
            pulse?.cancel(); pulse = null; orb.scaleX = 1f; orb.scaleY = 1f
        }
    }

    override fun onLog(who: String, msg: String) {
        logView.append("$who: $msg\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
