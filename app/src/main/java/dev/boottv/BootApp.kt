package dev.boottv

import android.app.Application

class BootApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        dev.boottv.archive.ArchiveDownloads.init(this)
        dev.boottv.archive.ArchiveProgress.init(this)
        // As device owner, make sure Home still leads to BootTV (an update can reset it).
        dev.boottv.owner.DeviceControl.ensure(this)
    }

    companion object {
        lateinit var instance: BootApp
            private set
    }
}
