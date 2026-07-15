package com.jarvis.app

import android.app.Application
import moe.shizuku.api.Shizuku

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Shizuku.initialize(this)
    }
}
