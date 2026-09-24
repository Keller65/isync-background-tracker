package expo.modules.isyncbackgroundlocation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.lang.ref.WeakReference

internal const val TAG = "IsyncBgLocation"
internal const val EVENT_LOCATION = "location"
internal const val EVENT_GEOVALLA = "geovalla"

class TrackingOptions : Record {
  @Field val interval: Long = 2_000

  @Field val distanceInterval: Float = 1f

  @Field val deviceCode: String = ""

  @Field val deviceName: String = ""

  @Field val movementConfirmMs: Long = DEFAULT_MOVEMENT_CONFIRM_MS
}

class IsyncBackgroundLocationModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val fusedClient: FusedLocationProviderClient by lazy {
    LocationServices.getFusedLocationProviderClient(context)
  }

  override fun definition() = ModuleDefinition {
    Name("IsyncBackgroundLocation")

    Events(EVENT_LOCATION, EVENT_GEOVALLA)

    OnCreate {
      instance = WeakReference(this@IsyncBackgroundLocationModule)
    }

    OnDestroy {
      instance = null
    }

    AsyncFunction("getStatus") {
      return@AsyncFunction mapOf(
        "available" to true,
        "isRunning" to IsyncBackgroundLocationService.isRunning
      )
    }

    AsyncFunction("getCurrentLocation") { promise: Promise ->
      val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
        .setMaxUpdateAgeMillis(5_000)
        .build()

      try {
        fusedClient.getCurrentLocation(request, null)
          .addOnSuccessListener { location: Location? ->
            if (location == null) {
              promise.reject("E_NO_LOCATION", "No se pudo obtener una ubicación", null)
            } else {
              promise.resolve(location.toEventMap())
            }
          }
          .addOnFailureListener { error ->
            promise.reject("E_LOCATION_FAILED", error.message ?: "Fallo al obtener ubicación", error)
          }
      } catch (e: SecurityException) {
        promise.reject("E_NO_PERMISSION", "Falta permiso de ubicación", e)
      }
    }

    AsyncFunction("start") { options: TrackingOptions ->
      val intent = Intent(context, IsyncBackgroundLocationService::class.java).apply {
        putExtra(EXTRA_INTERVAL, options.interval)
        putExtra(EXTRA_DISTANCE, options.distanceInterval)
        putExtra(EXTRA_DEVICE_CODE, options.deviceCode)
        putExtra(EXTRA_DEVICE_NAME, options.deviceName)
        putExtra(EXTRA_MOVEMENT_CONFIRM_MS, options.movementConfirmMs)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
      setWasTracking(true)
      return@AsyncFunction null
    }

    AsyncFunction("stop") {
      context.stopService(Intent(context, IsyncBackgroundLocationService::class.java))
      setWasTracking(false)
      return@AsyncFunction null
    }

    AsyncFunction("requestPermissions") { promise: Promise ->
      val foregroundPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        foregroundPermissions += Manifest.permission.ACTIVITY_RECOGNITION
      }

      Permissions.askForPermissionsWithPermissionsManager(
        appContext.permissions,
        object : Promise {
          override fun resolve(value: Any?) {
            val foregroundGranted = (value as? Map<*, *>)?.get("granted") as? Boolean ?: false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !foregroundGranted) {
              promise.resolve(value)
              return
            }
            Permissions.askForPermissionsWithPermissionsManager(
              appContext.permissions,
              promise,
              Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
          }

          override fun reject(code: String?, message: String?, cause: Throwable?) {
            promise.reject(code, message, cause)
          }
        },
        *foregroundPermissions.toTypedArray()
      )
    }

    AsyncFunction("getMotionState") {
      return@AsyncFunction IsyncBackgroundLocationService.motionSnapshot()
    }

    AsyncFunction("getPendingPoints") { limit: Int ->
      return@AsyncFunction TrackingDatabase.getInstance(context).getPending(limit)
    }

    AsyncFunction("getPendingPointsCount") {
      return@AsyncFunction TrackingDatabase.getInstance(context).count()
    }

    AsyncFunction("deletePoints") { ids: List<Double> ->
      TrackingDatabase.getInstance(context).deleteByIds(ids.map { it.toLong() })
      return@AsyncFunction null
    }

    AsyncFunction("startBatchSync") {
      BatchSyncWorker.schedulePeriodic(context)
      BatchSyncWorker.schedule(context)
      return@AsyncFunction null
    }

    AsyncFunction("stopBatchSync") {
      BatchSyncWorker.cancelPeriodic(context)
      BatchSyncWorker.cancel(context)
      return@AsyncFunction null
    }

    AsyncFunction("getGeoVallas") {
      return@AsyncFunction GeoVallasServices.getInstance(context).cachedOrBoot().map { it.toMap() }
    }

    AsyncFunction("refreshGeoVallas") { promise: Promise ->
      GeoVallasServices.getInstance(context).refresh(
        onDone = { list -> promise.resolve(list.map { it.toMap() }) },
        onError = { msg -> promise.reject("E_GEOVALLAS_REFRESH", msg ?: "Error al descargar geovallas", null) }
      )
    }

    AsyncFunction("setGeovallaNotificationsEnabled") { enabled: Boolean ->
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_GEOVALLA_NOTIFICATIONS, enabled)
        .apply()
      return@AsyncFunction null
    }

    AsyncFunction("getGeovallaNotificationsEnabled") {
      return@AsyncFunction context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_GEOVALLA_NOTIFICATIONS, true)
    }
  }

  private fun setWasTracking(value: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(PREF_WAS_TRACKING, value)
      .apply()
  }

  companion object {
    private var instance: WeakReference<IsyncBackgroundLocationModule>? = null

    fun emitLocation(payload: Map<String, Any?>) {
      val module = instance?.get() ?: return
      try {
        module.sendEvent(EVENT_LOCATION, payload)
      } catch (e: Throwable) {
        Log.e(TAG, "emitLocation: fallo", e)
      }
    }

    fun emitGeoVallaEvent(payload: Map<String, Any?>) {
      val module = instance?.get() ?: return
      try {
        module.sendEvent(EVENT_GEOVALLA, payload)
      } catch (e: Throwable) {
        Log.e(TAG, "emitGeoVallaEvent: fallo", e)
      }
    }
  }
}

internal fun Location.toEventMap(): Map<String, Any?> = mapOf(
  "latitude" to latitude,
  "longitude" to longitude,
  "accuracy" to accuracy,
  "altitude" to altitude,
  "speed" to speed,
  "heading" to bearing,
  "timestamp" to time
)