package com.jarvis.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.NotificationManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DeviceBridge(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null
    private var recognitionResult = ""
    private var recognitionActive = false

    private val appMap = mutableMapOf<String, String>()

    init {
        try {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) tts = null
            }
        } catch (_: Exception) {}
        scanInstalledApps()
    }

    private fun scanInstalledApps() {
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(mainIntent, 0)
            for (ri in activities) {
                val label = ri.loadLabel(pm).toString().trim()
                val pkg = ri.activityInfo.packageName
                // Map by lowercase app name
                val clean = label.replace(Regex("[^a-zA-Z0-9 ]"), "").lowercase().trim()
                if (clean.length >= 2 && clean !in appMap) appMap[clean] = pkg
                // Also map by last part of package name (e.g. "chrome" for "com.android.chrome")
                val short = pkg.split(".").lastOrNull()?.lowercase() ?: continue
                if (short.length >= 2 && short !in appMap) appMap[short] = pkg
            }
        } catch (_: Exception) {}
    }

    // ===== DEVICE CONTROL =====

    @android.webkit.JavascriptInterface
    fun execute(action: String): String {
        return try {
            when {
                action == "wifi_on" || action == "wifi_off" -> setWifi(action == "wifi_on")
                action == "bt_on" || action == "bt_off" -> setBluetooth(action == "bt_on")
                action == "lock_screen" -> shizukuRun("input keyevent 26")
                action == "dnd_on" || action == "dnd_off" -> setDnd(action == "dnd_on")
                action == "airplane_on" -> shizukuRun("cmd connectivity airplane-mode enable")
                action == "airplane_off" -> shizukuRun("cmd connectivity airplane-mode disable")
                action.startsWith("vol_") -> adjustVolume(action)
                action == "brightness_auto_on" || action == "brightness_auto_off" -> setBrightnessAuto(action == "brightness_auto_on")
                action == "screenshot" -> shizukuRun("screencap -p /sdcard/Pictures/jarvis_screenshot.png")
                action.startsWith("brightness_set:") -> {
                    val v = action.substringAfter(":").toIntOrNull()
                    if (v == null) "error:invalid brightness value" else setBrightness(v)
                }
                action.startsWith("open_") -> openApp(action.removePrefix("open_"))
                else -> "error:unknown action"
            }
        } catch (e: SecurityException) {
            "error:permission denied — ${e.message?.take(80)}"
        } catch (e: Exception) {
            "error:${e.message?.take(80) ?: "unknown"}"
        }
    }

    // ===== TEXT-TO-SPEECH (native + ElevenLabs) =====

    @android.webkit.JavascriptInterface
    fun speak(text: String, elevenLabsKey: String?) {
        try {
            tts?.stop()
            if (!elevenLabsKey.isNullOrBlank()) {
                speakElevenLabs(text, elevenLabsKey)
            } else {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        } catch (_: Exception) {}
    }

    @android.webkit.JavascriptInterface
    fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    @android.webkit.JavascriptInterface
    fun isTtsSpeaking(): Boolean = try { tts?.isSpeaking == true } catch (_: Exception) { false }

    private fun speakElevenLabs(text: String, apiKey: String) {
        Thread {
            try {
                val voiceId = "21m00Tcm4TlvDq8ikWAM" // Rachel
                val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("xi-api-key", apiKey)
                conn.doOutput = true

                val body = """{"text":${jsonEscape(text)},"model_id":"eleven_monolingual_v1","voice_settings":{"stability":0.5,"similarity_boost":0.75}}"""
                conn.outputStream.write(body.toByteArray())

                if (conn.responseCode != 200) {
                    // Fallback to native TTS
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                    return@Thread
                }

                val audioData = conn.inputStream.readBytes()
                val tempFile = File(context.cacheDir, "jarvis_voice.mp3")
                tempFile.writeBytes(audioData)

                val mp = MediaPlayer()
                mp.setDataSource(tempFile.absolutePath)
                mp.prepare()
                mp.start()
                mp.setOnCompletionListener { mp.release(); tempFile.delete() }
            } catch (_: Exception) {
                // Fallback
                try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun jsonEscape(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }

    // ===== SPEECH RECOGNITION =====

    @android.webkit.JavascriptInterface
    fun startListening(): Boolean {
        if (recognitionActive) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPerm = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return false
        }
        try {
            speech?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speech = recognizer
            recognitionResult = ""
            recognitionActive = true
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognitionResult = matches?.firstOrNull() ?: ""
                    recognitionActive = false
                }
                override fun onError(error: Int) {
                    recognitionActive = false
                    // Only clear result if we had nothing
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {
                    val matches = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) recognitionResult = matches[0]
                }
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer.startListening(intent)
            return true
        } catch (e: Exception) {
            recognitionActive = false
            return false
        }
    }

    @android.webkit.JavascriptInterface
    fun getRecognitionResult(): String = recognitionResult

    @android.webkit.JavascriptInterface
    fun isListening(): Boolean = recognitionActive

    @android.webkit.JavascriptInterface
    fun stopListening() {
        try { speech?.stopListening(); speech?.destroy() } catch (_: Exception) {}
        speech = null
        recognitionActive = false
    }

    @android.webkit.JavascriptInterface
    fun getInstalledApps(): String {
        return try {
            appMap.keys.joinToString(",")
        } catch (_: Exception) { "" }
    }

    // ===== PRIVATE HELPERS =====

    private fun setWifi(on: Boolean): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return if (wm.setWifiEnabled(on)) "ok" else "error:failed to toggle WiFi"
    }

    private fun setBluetooth(on: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return "error:grant Nearby devices permission in Settings"
        }
        val ba = BluetoothAdapter.getDefaultAdapter()
        if (ba == null) return "error:no Bluetooth hardware"
        val ok = if (on) ba.enable() else ba.disable()
        if (ok) return "ok"
        try {
            val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return "error:opened Bluetooth settings — toggle it manually"
        } catch (_: Exception) {
            return "error:Bluetooth toggle failed"
        }
    }

    private fun adjustVolume(action: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when (action) {
            "vol_up" -> AudioManager.ADJUST_RAISE
            "vol_down" -> AudioManager.ADJUST_LOWER
            "vol_mute" -> AudioManager.ADJUST_MUTE
            else -> return "error:unknown volume action"
        }
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
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        return "ok"
    }

    private fun setBrightnessAuto(on: Boolean): String {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return "ok"
    }

    private fun openApp(name: String): String {
        val query = name.lowercase().trim()
        // Try exact match first, then contains
        val pkg = appMap[query] ?: appMap.entries.find { query in it.key || it.key in query }?.value
            ?: return "error:app '$name' not found on your device"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) return "error:app '$name' is installed but cannot be launched"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "ok"
    }

    private fun shizukuRun(cmd: String): String {
        try {
            val clz = Class.forName("moe.shizuku.api.Shizuku")
            val ping = clz.getMethod("pingBinder")
            if (ping.invoke(null) as? Boolean != true) return "error:install Shizuku app for this feature"
            val newProcess = clz.getMethod("newProcess", Array<String>::class.java, String::class.java, String::class.java)
            val process = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            return if (process.waitFor() == 0) "ok" else "error:command failed"
        } catch (_: Exception) {
            return "error:install Shizuku app for this feature"
        }
    }
}
