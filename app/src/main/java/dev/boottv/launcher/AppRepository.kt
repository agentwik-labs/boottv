package dev.boottv.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One launchable app ("channel") on the home screen. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val isLeanback: Boolean,
    val isSystem: Boolean,
)

/** Artwork for a tile: a TV banner (16:9) when the app has one, else its launcher icon. */
data class AppArt(val bitmap: ImageBitmap, val isBanner: Boolean)

object AppRepository {
    private val artCache = LruCache<String, AppArt>(120)

    /** Queries LEANBACK_LAUNCHER and LAUNCHER activities; one entry per package, TV activity preferred. */
    suspend fun loadApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val self = context.packageName
        val result = LinkedHashMap<String, AppEntry>()

        fun add(infos: List<ResolveInfo>, leanback: Boolean) {
            for (ri in infos) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == self || result.containsKey(pkg)) continue
                if (!ai.enabled || !ai.applicationInfo.enabled) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: pkg
                result[pkg] = AppEntry(
                    packageName = pkg,
                    label = label,
                    component = ComponentName(pkg, ai.name),
                    isLeanback = leanback,
                    isSystem = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (ai.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                )
            }
        }

        add(query(pm, Intent.CATEGORY_LEANBACK_LAUNCHER), leanback = true)
        add(query(pm, Intent.CATEGORY_LAUNCHER), leanback = false)
        result.values.sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    }

    /** Clears cached artwork for a package (after an update it may have new art). */
    fun invalidate(pkg: String?) {
        if (pkg == null) artCache.evictAll() else artCache.remove(pkg)
    }

    suspend fun loadArt(context: Context, app: AppEntry): AppArt? {
        artCache.get(app.packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val banner: Drawable? = runCatching { pm.getActivityBanner(app.component) }.getOrNull()
                ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
            val art = if (banner != null) {
                AppArt(banner.render(320, 180), isBanner = true)
            } else {
                val icon = runCatching { pm.getActivityIcon(app.component) }.getOrNull()
                    ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                icon?.let { AppArt(it.render(192, 192), isBanner = false) }
            }
            art?.also { artCache.put(app.packageName, it) }
        }
    }

    private fun Drawable.render(defaultW: Int, defaultH: Int): ImageBitmap {
        val w = if (intrinsicWidth > 0) intrinsicWidth.coerceAtMost(defaultW * 2) else defaultW
        val h = if (intrinsicHeight > 0) intrinsicHeight.coerceAtMost(defaultH * 2) else defaultH
        return toBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    /** Starts the app. Returns false if it can't be launched. */
    fun launch(context: Context, pkg: String): Boolean {
        val intent = launchIntent(context, pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun openAppInfo(context: Context, pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    fun uninstall(context: Context, pkg: String) {
        val uri = Uri.parse("package:$pkg")
        val primary = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(primary) }.isFailure) {
            runCatching { context.startActivity(fallback) }
        }
    }
}
