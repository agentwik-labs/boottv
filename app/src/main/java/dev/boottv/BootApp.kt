package dev.boottv

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder

class BootApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        dev.boottv.archive.ArchiveDownloads.init(this)
        dev.boottv.archive.ArchiveProgress.init(this)
        dev.boottv.localmedia.LocalProgress.init(this)
        // As device owner, make sure Home still leads to BootTV (an update can reset it).
        dev.boottv.owner.DeviceControl.ensure(this)
    }

    /** Images everywhere: animated sprites (GIF) and a frame from your own videos when they have no poster. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .build()

    companion object {
        lateinit var instance: BootApp
            private set
    }
}
