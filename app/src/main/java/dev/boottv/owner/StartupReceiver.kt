package dev.boottv.owner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the TV starts or BootTV updates: put BootTV's control back (Home → BootTV, stock home screen hidden). */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeviceControl.ensure(context)
    }
}
