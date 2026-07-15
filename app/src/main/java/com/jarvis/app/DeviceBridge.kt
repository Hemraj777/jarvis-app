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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeviceBridge(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null
    private var recognitionResult = ""
    private var recognitionActive = false
    private val appMap = mutableMapOf<String, String>()

    init {
        // Blocking TTS init — guarantees it's ready when speak() is called
        val latch = CountDownLatch(1)
        try {
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) tts = null
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        // Always create a fallback TTS if init failed
        if (tts == null) {
            try { tts = TextToSpeech(context) { _ -> } } catch (_: Exception) {}
        }

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
                val clean = label.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                if (clean.length >= 2) appMap[clean] = pkg
                val short = pkg.split(".").lastOrNull()?.lowercase() ?: continue
                if (short.length >= 2) appMap[short] = pkg
            }
        } catch (_: Exception) {}
    }

    @android.webkit.JavascriptInterface
    fun ping(): Boolean = true

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
                    if (v == null) "error:bad value" else setBrightness(v)
                }
                action.startsWith("open_") -> openApp(action.removePrefix("open_"))
                else -> "error:unknown action"
            }
        } catch (e: SecurityException) {
            "error:perm denied — ${e.message?.take(60)}"
        } catch (e: Exception) {
            "error:${e.message?.take(60) ?: "unknown"}"
        }
    }

    // ===== TTS =====

    @android.webkit.JavascriptInterface
    fun speak(text: String) {
        speakWithKey(text, "")
    }

    @android.webkit.JavascriptInterface
    fun speakWithKey(text: String, elevenLabsKey: String) {
        try {
            tts?.stop()
        } catch (_: Exception) {}

        if (elevenLabsKey.isNotBlank()) {
            speakElevenLabs(text, elevenLabsKey)
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
                val voiceId = "21m00Tcm4TlvDq8ikWAM"
                val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("xi-api-key", apiKey)
                conn.doOutput = true
                val body = """{"text":${jsonEsc(text)},"model_id":"eleven_monolingual_v1","voice_settings":{"stability":0.5,"similarity_boost":0.75}}"""
                conn.outputStream.write(body.toByteArray())
                if (conn.responseCode != 200) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                    return@Thread
                }
                val audio = conn.inputStream.readBytes()
                val f = File(context.cacheDir, "jv.mp3")
                f.writeBytes(audio)
                val mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.prepare()
                mp.start()
                mp.setOnCompletionListener { mp.release(); f.delete() }
            } catch (_: Exception) {
                try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun jsonEsc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    // ===== SPEECH RECOGNITION =====

    @android.webkit.JavascriptInterface
    fun startListening(): Boolean {
        if (recognitionActive) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        try {
            speech?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(context) ?: return false
            speech = r
            recognitionResult = ""
            recognitionActive = true
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) {
                    val m = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognitionResult = m?.firstOrNull() ?: ""
                    recognitionActive = false
                }
                override fun onError(e: Int) { recognitionActive = false }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {
                    val m = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!m.isNullOrEmpty()) recognitionResult = m[0]
                }
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            return true
        } catch (_: Exception) {
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
        speech = null; recognitionActive = false
    }

    @android.webkit.JavascriptInterface
    fun getInstalledApps(): String = appMap.keys.joinToString(",")

    // ===== PRIVATE =====

    private fun setWifi(on: Boolean): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return if (wm.setWifiEnabled(on)) "ok" else "error:failed"
    }

    private fun setBluetooth(on: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                return "error:grant Nearby devices permission"
        }
        val ba = BluetoothAdapter.getDefaultAdapter() ?: return "error:no BT"
        if (if (on) ba.enable() else ba.disable()) return "ok"
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "error:opened BT settings"
        } catch (_: Exception) { return "error:BT toggle failed" }
    }

    private fun adjustVolume(a: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            when (a) { "vol_up" -> AudioManager.ADJUST_RAISE; "vol_down" -> AudioManager.ADJUST_LOWER
                "vol_mute" -> AudioManager.ADJUST_MUTE; else -> return "error:bad" }, 0)
        return "ok"
    }

    private fun setDnd(on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return "error:grant DND access"
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return "ok"
    }

    private fun setBrightness(v: Int): String {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(0, 255))
        return "ok"
    }

    private fun setBrightnessAuto(on: Boolean): String {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return "ok"
    }

    private fun openApp(name: String): String {
        val q = name.lowercase().trim()
        val pkg = appMap[q] ?: appMap.entries.find { q in it.key || it.key in q }?.value ?: return "error:app '$name' not found"
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "error:app '$name' cannot launch"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return "ok"
    }

    private fun shizukuRun(cmd: String): String {
        try {
            val c = Class.forName("moe.shizuku.api.Shizuku")
            val ping = c.getMethod("pingBinder")
            if (ping.invoke(null) as? Boolean != true) return "error:install Shizuku"
            val p = c.getMethod("newProcess", Array<String>::class.java, String::class.java, String::class.java)
            val proc = p.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            return if (proc.waitFor() == 0) "ok" else "error:cmd failed"
        } catch (_: Exception) { return "error:install Shizuku" }
    }
}
