package pl.jassi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Usługa pierwszoplanowa: trzyma mikrofon i pozwala sterować telefonem głosem (słowo-klucz „JasSI”) bez dotykania ekranu. */
class ListenService : Service() {
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jassi_listen", "JasSI słucha", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ListenService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jassi_listen")
            .setContentTitle("JasSI słucha")
            .setContentText("Powiedz „JasSI” i polecenie")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(@Suppress("DEPRECATION") Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Wyłącz", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)

        Engine.start()
        Engine.startContinuous()
        addOverlay()
        return START_NOT_STICKY
    }

    /** Niewidoczne okienko 1x1 px – pozwala systemowi uruchamiać aplikacje z tła (wymaga zgody „wyświetlaj nad innymi”). */
    private fun addOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        try {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.START or Gravity.TOP }
            getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v
        } catch (e: Exception) { /* brak zgody – trudno */ }
    }

    override fun onDestroy() {
        Engine.stopContinuous()
        overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (e: Exception) {} }
        overlay = null
        super.onDestroy()
    }
}
