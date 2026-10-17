package dev.boottv

import android.app.Application

class BootApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: BootApp
            private set
    }
}
