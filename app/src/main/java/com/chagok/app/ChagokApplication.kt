package com.chagok.app

import android.app.Application

class ChagokApplication : Application() {
    lateinit var engine: ChagokEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = ChagokEngine(this).also { it.start() }
    }
}
