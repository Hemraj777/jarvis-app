package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import moe.shizuku.api.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: DeviceBridge

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_WRITE_SETTINGS = 1002
        private const val REQ_NOTIFICATION_POLICY = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = DeviceBridge(this)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.grant(request.resources)
            }
        }

        val url = getString(R.string.jarvis_url)
        webView.loadUrl(url)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                ),
                0
            )
        }

        if (!Settings.System.canWrite(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                },
                REQ_WRITE_SETTINGS
            )
        }

        if (!NotificationManagerCompat.from(this).isNotificationPolicyAccessGranted) {
            startActivityForResult(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                REQ_NOTIFICATION_POLICY
            )
        }

        if (Shizuku.pingBinder()) {
            try {
                Shizuku.requestPermission(REQ_SHIZUKU)
            } catch (_: Exception) {}
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
