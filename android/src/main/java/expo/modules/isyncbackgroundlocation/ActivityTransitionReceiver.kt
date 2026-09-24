package expo.modules.isyncbackgroundlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult

class ActivityTransitionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) return
    val result = ActivityTransitionResult.extractResult(intent) ?: return

    result.transitionEvents.forEach { event ->
      IsyncBackgroundLocationService.onActivityTransition(event.activityType, event.transitionType)
    }
  }
}