package com.jarvis.app

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.app.NotificationManager
import android.util.Log
import moe.shizuku.api.Shizuku

class DeviceBridge(private val context: Context) {

    private val appPkg = mapOf(
        "whatsapp"  to "com.whatsapp",
        "youtube"   to "com.google.android.youtube",
        "chrome"    to "com.android.chrome",
        "gmail"     to "com.google.android.gm",
        "maps"      to "com.google.android.apps.maps",
        "instagram" to "com.instagram.android",
        "spotube"   to "com.bervan.spotube"
    )

    @android.webkit.JavascriptInterface
    fun execute(action: String): Boolean {
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
                    val v = action.substringAfter(":").toIntOrNull() ?: return false
                    setBrightness(v)
                }
                action.startsWith("open_") -> openApp(action.removePrefix("open_"))
                else -> false
            }
        } catch (e: Exception) {
            Log.e("DeviceBridge", "execute error", e)
            false
        }
    }

    private fun setWifi(on: Boolean): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.setWifiEnabled(on)
    }

    private fun setBluetooth(on: Boolean): Boolean {
        val ba = BluetoothAdapter.getDefaultAdapter() ?: return false
        return if (on) ba.enable() else ba.disable()
    }

    private fun adjustVolume(dir: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        return true
    }

    private fun setDnd(on: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return false
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return true
    }

    private fun setBrightness(value: Int): Boolean {
        val v = value.coerceIn(0, 255)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        return true
    }

    private fun setBrightnessAuto(on: Boolean): Boolean {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        return true
    }

    private fun openApp(name: String): Boolean {
        val pkg = appPkg[name] ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun shizukuRun(cmd: String): Boolean {
        if (!Shizuku.pingBinder()) return false
        val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val exit = process.waitFor()
        return exit == 0
    }
}
