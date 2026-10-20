package dev.boottv.owner

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** BootTV's device-admin component; as device owner it applies the stick-wide settings. */
class BootAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        DeviceControl.applyOnBecomingOwner(context)
    }
}
