package expo.modules.isyncbackgroundlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

    val wasTracking = context
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(PREF_WAS_TRACKING, false)

    if (!wasTracking) return

    Log.d(TAG, "BOOT_COMPLETED: tracking estaba activo, relanzando servicio")

    val serviceIntent = Intent(context, IsyncBackgroundLocationService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(serviceIntent)
    } else {
      context.startService(serviceIntent)
    }
  }
}