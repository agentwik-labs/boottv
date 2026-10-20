package dev.boottv.owner

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.util.Log
import dev.boottv.MainActivity

/**
 * What BootTV does when it is the stick's device owner (Android's kiosk / managed-TV mode, set up with
 * tools/owner.sh on a freshly reset stick). Everything here is undone by [release].
 */
object DeviceControl {
    private const val TAG = "DeviceControl"
    private const val PREFS = "device_control"
    private const val KEY_HIDDEN = "hidden_packages"

    /** Google's home screens and the ad / recommendation / telemetry services that feed them. */
    val stockPackages = listOf(
        "com.google.android.apps.tv.launcherx" to "Google TV home screen",
        "com.google.android.tvlauncher" to "Android TV home screen",
        "com.google.android.leanbacklauncher" to "Old Android TV home screen",
        "com.google.android.tvrecommendations" to "Sponsored recommendations",
        "com.google.android.leanbacklauncher.recommendations" to "Recommendation rows",
        "com.google.android.feedback" to "Usage feedback reporting",
        "com.google.android.tv.bugreportsender" to "Automatic bug reports",
    )

    /** Lockdown: nobody can wipe the stick, boot around BootTV or add other profiles. */
    private val lockRestrictions = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
    )

    enum class UpdateMode(val label: String) { AUTOMATIC("Automatic"), OVERNIGHT("Overnight, 2–5 AM"), POSTPONE("Postpone 30 days") }

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, BootAdminReceiver::class.java)

    fun isOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    /** Run once when BootTV becomes owner: permanent Home and the stock home screen hidden. */
    fun applyOnBecomingOwner(context: Context) {
        if (!isOwner(context)) return
        setPermanentHome(context)
        setStockHidden(context, true)
    }

    /** Home always opens BootTV, whatever other launchers are installed or come back with an update. */
    fun setPermanentHome(context: Context) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm(context).addPersistentPreferredActivity(admin(context), filter, ComponentName(context, MainActivity::class.java))
    }

    // ---- Stock home screen and ad services ----------------------------------------------------

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES) }.isSuccess

    fun hiddenPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    /** Hides (doesn't uninstall) the stock home screen and ad services, or brings them back. */
    fun setStockHidden(context: Context, hide: Boolean): Int {
        val dpm = dpm(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = hiddenPackages(context).toMutableSet()
        var changed = 0
        val targets = if (hide) stockPackages.map { it.first }.filter { installed(context, it) } else done.toList()
        for (pkg in targets) {
            runCatching { dpm.setApplicationHidden(admin(context), pkg, hide) }
                .onSuccess { ok -> if (ok) { changed++; if (hide) done += pkg else done -= pkg } }
                .onFailure { Log.w(TAG, "hide $pkg", it) }
        }
        prefs.edit().putStringSet(KEY_HIDDEN, done).apply()
        return changed
    }

    // ---- Lockdown -----------------------------------------------------------------------------

    fun isLocked(context: Context): Boolean {
        val r = dpm(context).getUserRestrictions(admin(context))
        return lockRestrictions.all { r.getBoolean(it) }
    }

    fun setLocked(context: Context, lock: Boolean) {
        val dpm = dpm(context)
        lockRestrictions.forEach { if (lock) dpm.addUserRestriction(admin(context), it) else dpm.clearUserRestriction(admin(context), it) }
    }

    // ---- System updates -----------------------------------------------------------------------

    fun updateMode(context: Context): UpdateMode = when (dpm(context).systemUpdatePolicy?.policyType) {
        SystemUpdatePolicy.TYPE_INSTALL_WINDOWED -> UpdateMode.OVERNIGHT
        SystemUpdatePolicy.TYPE_POSTPONE -> UpdateMode.POSTPONE
        else -> UpdateMode.AUTOMATIC
    }

    fun setUpdateMode(context: Context, mode: UpdateMode) {
        val policy = when (mode) {
            UpdateMode.AUTOMATIC -> null
            UpdateMode.OVERNIGHT -> SystemUpdatePolicy.createWindowedInstallPolicy(2 * 60, 5 * 60)
            UpdateMode.POSTPONE -> SystemUpdatePolicy.createPostponeInstallPolicy()
        }
        dpm(context).setSystemUpdatePolicy(admin(context), policy)
    }

    // ---- Giving control back ------------------------------------------------------------------

    /** Undoes everything above and stops being device owner; the stick is back to normal. */
    fun release(context: Context) {
        if (!isOwner(context)) return
        val dpm = dpm(context)
        runCatching { setStockHidden(context, false) }
        runCatching { setLocked(context, false) }
        runCatching { dpm.setSystemUpdatePolicy(admin(context), null) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin(context), context.packageName) }
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
    }
}
