package dev.boottv.livetv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Your cable / TV provider's own app (Spectrum). Its channels can't be pulled into BootTV's guide, so BootTV makes the
 * app one press away instead: optionally the Live TV entry and the remote's Guide button open it.
 */
object TvProvider {
    const val ROUTE = "app:provider"
    const val NAME = "Spectrum TV"
    /** The Google TV edition first, then the older phone/tablet app. */
    private val packages = listOf("com.spectrum.stva.androidtv", "com.TWCableTV")
    private const val PREFS = "tv_provider"
    private const val KEY_LIVE = "live_opens_provider"

    fun installedPackage(context: Context): String? = packages.firstOrNull { launchIntent(context, it) != null }

    private fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)

    /** Opens the provider's app, or its Google Play page when it isn't installed. */
    fun open(context: Context) {
        // Spectrum's app loses remote focus when it's resumed from the background (arrows stop working),
        // so open it on a fresh screen each time instead of bringing back the old one.
        val launch = installedPackage(context)?.let { launchIntent(context, it) }?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${packages.first()}"))
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${packages.first()}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Live TV (side menu) and the remote's Guide / TV buttons open the provider's app instead of BootTV's guide. */
    fun liveOpensProvider(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE, false) && installedPackage(context) != null

    fun setLiveOpensProvider(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE, on).apply()

    fun liveOpensProviderSetting(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE, false)
}
