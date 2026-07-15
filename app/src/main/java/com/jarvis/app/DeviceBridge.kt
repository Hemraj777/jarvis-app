package com.jarvis.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class DeviceBridge(private val context: Context) {

    private var tts: TextToSpeech? = null

    private val appPkg = mapOf(
        "whatsapp"  to "com.whatsapp",
        "youtube"   to "com.google.android.youtube",
        "chrome"    to "com.android.chrome",
        "gmail"     to "com.google.android.gm",
        "maps"      to "com.google.android.apps.maps",
        "instagram" to "com.instagram.android",
        "spotube"   to "com.bervan.spotube"
    )

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    tts = null
                }
            }
        } catch (_: Exception) {}
    }

    @android.webkit.JavascriptInterface
    fun execute(action: String): String {
        return try {
            when {
                action == "wifi_on" -> setWifi(true)
                action == "wifi_off" -> setWifi(false)
                action == "bt_on" -> setBluetooth(true)
                action == "bt_off" -> setBluetooth(false)
                action == "lock_screen" -> shizukuRun("input keyevent 26")
                action == "dnd_on" -> setDnd(true)
                action == "dnd_off" -> setDnd(false)
                action == "airplane_on" -> shizukuRun("cmd connectivity airplane-mode enable")
                action == "airplane_off" -> shizukuRun("cmd connectivity airplane-mode disable")
                action == "vol_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
                action == "vol_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
                action == "vol_mute" -> adjustVolume(AudioManager.ADJUST_MUTE)
                action == "brightness_auto_on" -> setBrightnessAuto(true)
                action == "brightness_auto_off" -> setBrightnessAuto(false)
                action == "screenshot" -> shizukuRun("screencap -p /sdcard/Pictures/jarvis_screenshot.png")
                action.startsWith("brightness_set:") -> {
                    val v = action.substringAfter(":").toIntOrNull()
                    if (v == null) "error:invalid brightness value"
                    else setBrightness(v)
                }
                action.startsWith("open_") -> openApp(action.removePrefix("open_"))
                else -> "error:unknown action"
            }
        } catch (e: SecurityException) {
            Log.e("DeviceBridge", "permission denied", e)
            "error:permission denied - ${e.message?.take(80)}"
        } catch (e: Exception) {
            Log.e("DeviceBridge", "execute error", e)
            "error:${e.message?.take(80) ?: "unknown"}"
        }
    }

    @android.webkit.JavascriptInterface
    fun speak(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } catch (_: Exception) {}
    }

    @android.webkit.JavascriptInterface
    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    @android.webkit.JavascriptInterface
    fun isSpeaking(): Boolean {
        return try {
            tts?.isSpeaking == true
        } catch (_: Exception) {
            false
        }
    }

    private fun setWifi(on: Boolean): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return if (wm.setWifiEnabled(on)) "ok" else "error:failed to toggle WiFi"
    }

    private fun setBluetooth(on: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return "error:grant BLUETOOTH_CONNECT permission in Settings"
        }
        val ba = BluetoothAdapter.getDefaultAdapter()
        if (ba == null) return "error:no Bluetooth hardware"
        val ok = if (on) ba.enable() else ba.disable()
        return if (ok) "ok" else "error:Bluetooth toggle failed"
    }

    private fun adjustVolume(dir: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        return "ok"
    }

    private fun setDnd(on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return "error:grant DND access in Settings"
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return "ok"
    }

    private fun setBrightness(value: Int): String {
        val v = value.coerceIn(0, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        return "ok"
    }

    private fun setBrightnessAuto(on: Boolean): String {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        return "ok"
    }

    private fun openApp(name: String): String {
        val pkg = appPkg[name] ?: return "error:unknown app '$name'"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) return "error:app '$name' not installed"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "ok"
    }

    private fun shizukuRun(cmd: String): String {
        try {
            val clz = Class.forName("moe.shizuku.api.Shizuku")
            val ping = clz.getMethod("pingBinder")
            if (ping.invoke(null) as? Boolean != true) return "error:install Shizuku for this feature"
            val newProcess = clz.getMethod("newProcess", Array<String>::class.java, String::class.java, String::class.java)
            val process = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val exit = process.waitFor()
            return if (exit == 0) "ok" else "error:command failed (exit $exit)"
        } catch (e: Exception) {
            return "error:install Shizuku for this feature"
        }
    }
}
