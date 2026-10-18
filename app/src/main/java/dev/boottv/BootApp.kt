package dev.boottv

import android.app.Application

class BootApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        dev.boottv.archive.ArchiveDownloads.init(this)
    }

    companion object {
        lateinit var instance: BootApp
            private set
    }
}
