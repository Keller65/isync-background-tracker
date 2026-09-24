package expo.modules.isyncbackgroundlocation

import android.content.ComponentCallbacks2
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.HandlerThread
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.lang.ref.WeakReference

internal const val EXTRA_INTERVAL = "interval"
internal const val EXTRA_DISTANCE = "distanceInterval"
internal const val WS_URL = "https://alfayomega.isynchn.com"
internal const val EXTRA_DEVICE_CODE = "deviceCode"
internal const val EXTRA_DEVICE_NAME = "deviceName"

internal const val PREFS_NAME = "isync_background_location_prefs"
internal const val PREF_WAS_TRACKING = "was_tracking"
internal const val PREF_IS_STATIONARY = "is_stationary"
internal const val PREF_STATIONARY_LAT = "stationary_lat"
internal const val PREF_STATIONARY_LNG = "stationary_lng"
internal const val PREF_GEOVALLA_NOTIFICATIONS = "geovalla_notifications"

private const val CHANNEL_ID = "isync_background_location"
private const val NOTIFICATION_ID = 9001

private const val TRACKING_ACCURACY_THRESHOLD = 20f
private const val MAX_SPEED_MPS = 55f
private const val GAP_GRACE_PERIOD_MS = 5 * 60_000L
private const val ELASTICITY_SPEED_STEP_MPS = 5f
private const val ELASTICITY_MULTIPLIER = 1f
private const val MAX_ELASTIC_FACTOR = 3f
private const val MAX_RECORDS_TO_PERSIST = 100_000
private const val MAX_DAYS_TO_PERSIST = 7L
private const val MILLIS_PER_DAY = 86_400_000L
private const val STILL_CONFIRM_MS = 5 * 60_000L
internal const val DEFAULT_MOVEMENT_CONFIRM_MS = 15_000L
internal const val EXTRA_MOVEMENT_CONFIRM_MS = "movementConfirmMs"
private const val STOP_DETECTION_RADIUS_METERS = 15f
private const val GPS_MOTION_THRESHOLD_MPS = 0.5f
private const val MIN_DISPLACEMENT_METERS = 2f

private class PositionKalmanFilter(private val processNoiseMps: Float) {
  private var timestampMs = 0L
  private var lat = 0.0
  private var lng = 0.0
  private var variance = -1f

  fun filter(location: Location): Location {
    val measurementVariance = location.accuracy * location.accuracy

    if (variance < 0f) {
      timestampMs = location.time
      lat = location.latitude
      lng = location.longitude
      variance = measurementVariance
    } else {
      val dtSeconds = (location.time - timestampMs) / 1000f
      if (dtSeconds > 0) {
        variance += dtSeconds * processNoiseMps * processNoiseMps
        timestampMs = location.time
      }
      val gain = variance / (variance + measurementVariance)
      lat += gain * (location.latitude - lat)
      lng += gain * (location.longitude - lng)
      variance *= (1 - gain)
    }

    return Location(location).apply {
      latitude = lat
      longitude = lng
    }
  }

  fun reset() {
    variance = -1f
  }
}

private const val KALMAN_PROCESS_NOISE_MPS = 3f

class IsyncBackgroundLocationService : Service() {
  private val fusedClient: FusedLocationProviderClient by lazy {
    LocationServices.getFusedLocationProviderClient(this)
  }

  private val trackingDb by lazy { TrackingDatabase.getInstance(this) }

  private val activityRecognitionClient: ActivityRecognitionClient by lazy {
    ActivityRecognition.getClient(this)
  }

  private var isStationary = false

  /**
   * GPS Espía activo: el GPS está encendido pero el modo estacionario sigue
   * activo.
   * El GPS es el árbitro: decide si fue un viaje real o un falso positivo,
   * sin ningún timer. La decisión ocurre en cada punto que llega del GPS.
   */
  private var isTentativeTracking = false
  private var tentativeTrackingStartedAt = 0L

  private var stillConfirmRunnable: Runnable? = null

  private var stopAnchor: Location? = null
  private var stopAnchorSince = 0L

  private val positionKalman = PositionKalmanFilter(KALMAN_PROCESS_NOISE_MPS)

  /**
   * Sensor de movimiento significativo de hardware puro.
   * No hace polling. El CPU duerme hasta que el hardware dispara el trigger.
   * Se re-arma manualmente después de cada decisión del GPS.
   */
  private val deviceMotionSensor = DeviceMotionSensor()

  private var movementConfirmMs = DEFAULT_MOVEMENT_CONFIRM_MS

  private var lastFixTimeMs = 0L

  private var lastGateLogAt = 0L

  private val locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      result.locations.forEach { rawLocation ->
        lastFixTimeMs = rawLocation.time
        processLocation(rawLocation)
      }
    }
  }

  /**
   * Procesa cada punto GPS recibido.
   *
   * Cuando [isTentativeTracking] está activo (GPS Espía), el GPS actúa como
   * árbitro sin ningún timer:
   *   - Si el punto está a más de [STOP_DETECTION_RADIUS_METERS] del ancla
   *     → es un viaje real. Se confirma y se sale del modo estacionario.
   *   - Si el punto llega con speed < [GPS_MOTION_THRESHOLD_MPS] y seguimos
   *     dentro del radio → es un falso positivo. Se apaga el GPS y se
   *     re-arma el sensor de hardware para que el CPU vuelva a dormir.
   */
  private fun processLocation(rawLocation: Location) {
    val prev = lastAccepted
    if (prev != null) {
      val gap = rawLocation.time - prev.time
      if (gap > GAP_GRACE_PERIOD_MS) {
        afterGap = true
      }
    }

    if (!isPlausible(rawLocation)) return
    val location = positionKalman.filter(rawLocation)

    // ── MODO GPS ESPÍA: el GPS arbitra la decisión sin timers ──────────────
    if (isStationary && isTentativeTracking) {
      val anchor = stopAnchor
      if (anchor == null) {
        isTentativeTracking = false
        exitStationaryMode()
        return
      }

      val distanceFromAnchor = anchor.distanceTo(location)
      val timeInTentativeMs = System.currentTimeMillis() - tentativeTrackingStartedAt

      when {
        // El GPS confirma desplazamiento real: ¡es un viaje!
        distanceFromAnchor > STOP_DETECTION_RADIUS_METERS -> {
          Log.d(TAG, "GPS Espía: desplazamiento real confirmado (${distanceFromAnchor.toInt()}m). Iniciando tracking.")
          deviceMotionSensor.disarm()
          isTentativeTracking = false
          exitStationaryMode()
          // No retornamos. Queremos que el punto caiga al flujo normal y se guarde.
        }

        // Si pasaron 30 segundos de GPS encendido y no cruzó los 25m ni registró velocidad:
        // es un falso positivo seguro. Apagamos el GPS y re-armamos el sensor.
        timeInTentativeMs > 30_000L -> {
          Log.d(TAG, "GPS Espía: timeout de 30s sin cruzar 25m. Falso positivo. Re-armando sensor.")
          stopTentativeTracking()
          deviceMotionSensor.arm()
          return
        }

        // Seguimos en los primeros 30s. Si detecta velocidad alta, podríamos confirmar el viaje rápido,
        // pero es más seguro esperar que cruce los 25m.
        else -> {
          // Descartamos el punto de los logs mientras esperamos decisión.
          return
        }
      }
    }
    // ── FIN MODO GPS ESPÍA ─────────────────────────────────────────────────

    updateStopDetection(location)
    if (!motionGatePasses(location)) return
    if (!passesElasticDistance(location)) return
    lastAccepted = location
    persistAndEmit(location)
    GeoVallasServices.getInstance(this).evaluate(location.latitude, location.longitude, location.time)
  }

  private fun persistAndEmit(location: Location) {
    try {
      val clientUuid = trackingDb.insert(location) ?: return
      LocationRelay.send(location, clientUuid)
      IsyncBackgroundLocationModule.emitLocation(location.toEventMap())
    } catch (e: Exception) {
      Log.e(TAG, "persistAndEmit: fallo", e)
    }
  }

  private var lastAccepted: Location? = null
  private var afterGap = false

  private var interval: Long = 5_000
  private var distanceInterval: Float = 1f
  private var deviceCode: String = ""
  private var deviceName: String = ""
  private var isTracking = false

  private val deviceId: String by lazy {
    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
  }

  private lateinit var workerThread: HandlerThread
  private lateinit var workerLooper: Looper

  private lateinit var maintenanceHandler: Handler
  private val maintenanceInterval = 600_000L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = WeakReference(this)
    workerThread = HandlerThread("IsyncBgLocationWorker").apply { start() }
    workerLooper = workerThread.looper
    maintenanceHandler = Handler(workerLooper)
    maintenanceHandler.post { runMaintenance() }
    scheduleMaintenance()

    // Geovallas (etapa 3): cargar la cache persistida a memoria y refrescar
    // desde el server. La evaluación dentro/fuera corre en workerLooper.
    val geoVallas = GeoVallasServices.getInstance(this)
    geoVallas.attach(maintenanceHandler)
    maintenanceHandler.post {
      geoVallas.boot()
      geoVallas.refresh()
    }

    // Inicializar el sensor de hardware (no lo arma todavía, solo detecta soporte).
    deviceMotionSensor.initialize(this, maintenanceHandler)
    deviceMotionSensor.onMotionDetected = {
      // Este callback llega en el workerThread (ya lo posteó el sensor).
      // Solo actuar si estamos estacionados y el GPS espía no está encendido.
      if (isStationary && !isTentativeTracking) {
        Log.d(TAG, "SignificantMotion trigger: iniciando GPS Espía.")
        startTentativeTracking()
      }
    }

    startForegroundNotification()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val startExtras = intent?.takeIf { it.hasExtra(EXTRA_INTERVAL) }
    val isExplicitStart = startExtras != null

    if (startExtras != null) {
      interval = startExtras.getLongExtra(EXTRA_INTERVAL, interval)
      distanceInterval = startExtras.getFloatExtra(EXTRA_DISTANCE, distanceInterval)
      deviceCode = startExtras.getStringExtra(EXTRA_DEVICE_CODE) ?: deviceCode
      deviceName = startExtras.getStringExtra(EXTRA_DEVICE_NAME) ?: deviceName
      movementConfirmMs = startExtras.getLongExtra(EXTRA_MOVEMENT_CONFIRM_MS, movementConfirmMs).coerceIn(0L, 120_000L)
      saveConfig()
    } else {
      restoreConfig()
    }

    if (isExplicitStart) {
      lastAccepted = null
      afterGap = false
      positionKalman.reset()
    }

    val restoredAnchor = if (isExplicitStart) null else readStationaryAnchor()
    when {
      restoredAnchor != null -> {
        maintenanceHandler.post {
          stopAnchor = restoredAnchor
          stopAnchorSince = System.currentTimeMillis()
          enterStationaryMode()
        }
      }
      else -> {
        maintenanceHandler.post {
          exitStationaryMode()
          startLocationTracking()
          requestFastFix()
        }
      }
    }

    requestActivityTransitionUpdates()
    LocationRelay.connect(this, WS_URL, deviceId, deviceCode, deviceName)
    isRunning = true
    maintenanceHandler.post {
      BatchSyncWorker.schedulePeriodic(this)
      BatchSyncWorker.schedule(this)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopLocationTracking()
    cancelStillTimer()
    removeActivityTransitionUpdates()
    saveStationaryState(null)
    instance = null
    LocationRelay.stop()
    GeoVallasServices.getInstance(this).release()
    maintenanceHandler.removeCallbacksAndMessages(null)
    deviceMotionSensor.release()
    maintenanceHandler.post { TrackingDatabase.getInstance(this).closeInstance() }
    workerThread.quitSafely()
    isRunning = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    super.onDestroy()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    when (level) {
      ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
      ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
        Log.e(TAG, "onTrimMemory($level): liberando recursos no esenciales")
        LocationBatchSync.getInstance(this).release()
      }
      ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
        Log.e(TAG, "onTrimMemory($level): liberando todo — proceso en riesgo")
        LocationRelay.releaseResources()
        LocationBatchSync.getInstance(this).release()
        GeoVallasServices.getInstance(this).release()
      }
    }
  }

  private var lastAccuracyLogAt = 0L

  private fun isPlausible(location: Location): Boolean {
    if (location.accuracy > TRACKING_ACCURACY_THRESHOLD) {
      val now = android.os.SystemClock.elapsedRealtime()
      if (now - lastAccuracyLogAt > 30_000L) {
        lastAccuracyLogAt = now
        Log.d(TAG, "Punto descartado por precisión baja: ${location.accuracy}m (límite: $TRACKING_ACCURACY_THRESHOLD)")
      }
      return false
    }

    val prev = lastAccepted ?: return true
    val dtMs = location.time - prev.time
    if (dtMs <= 0) return false

    if (afterGap && dtMs > GAP_GRACE_PERIOD_MS) {
      afterGap = false
      return true
    }

    val dtSeconds = dtMs / 1000.0
    return prev.distanceTo(location) / dtSeconds <= MAX_SPEED_MPS
  }

  private fun passesElasticDistance(location: Location): Boolean {
    val prev = lastAccepted ?: return true

    val distance = prev.distanceTo(location)
    val speed = if (location.hasSpeed()) {
      location.speed
    } else {
      val dtSeconds = (location.time - prev.time) / 1000.0
      if (dtSeconds > 0) (distance / dtSeconds).toFloat() else 0f
    }

    val factor = (speed / ELASTICITY_SPEED_STEP_MPS) * ELASTICITY_MULTIPLIER
    return distance >= (distanceInterval * factor.coerceIn(1f, MAX_ELASTIC_FACTOR)).coerceAtLeast(MIN_DISPLACEMENT_METERS)
  }

  /**
   * El gate de movimiento se basa en la velocidad del GPS y en el desplazamiento bruto.
   * En interiores, la velocidad del GPS a menudo es 0.0 m/s aunque el usuario camine.
   * Si la velocidad es baja, revisamos si se movió al menos 2.5 metros del último punto.
   */
  private fun motionGatePasses(location: Location): Boolean {
    if (location.hasSpeed() && location.speed >= GPS_MOTION_THRESHOLD_MPS) return true

    val prev = lastAccepted
    if (prev == null) return true // Aceptar siempre el primer punto del turno.
    if (prev.distanceTo(location) >= 2.5f) return true

    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastGateLogAt > 30_000L) {
      lastGateLogAt = now
      Log.d(TAG, "Gate de movimiento: punto descartado (speed=${location.speed}, dist al previo < 2.5m)")
    }
    return false
  }

  private fun startLocationTracking() {
    if (isTracking) {
      fusedClient.removeLocationUpdates(locationCallback)
      isTracking = false
    }

    positionKalman.reset()

    val request = LocationRequest.Builder(interval)
      .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
      .setMinUpdateIntervalMillis(interval)
      .setMinUpdateDistanceMeters(distanceInterval)
      .build()

    try {
      fusedClient.requestLocationUpdates(request, locationCallback, workerLooper)
      isTracking = true
    } catch (_: SecurityException) {
      stopSelf()
    }
  }

  private fun stopLocationTracking() {
    if (!isTracking) return
    fusedClient.removeLocationUpdates(locationCallback)
    isTracking = false
  }

  private fun saveConfig() {
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putLong(EXTRA_INTERVAL, interval)
      .putFloat(EXTRA_DISTANCE, distanceInterval)
      .putString(EXTRA_DEVICE_CODE, deviceCode)
      .putString(EXTRA_DEVICE_NAME, deviceName)
      .putLong(EXTRA_MOVEMENT_CONFIRM_MS, movementConfirmMs)
      .apply()
  }

  private fun restoreConfig() {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    interval = prefs.getLong(EXTRA_INTERVAL, interval)
    distanceInterval = prefs.getFloat(EXTRA_DISTANCE, distanceInterval)
    deviceCode = prefs.getString(EXTRA_DEVICE_CODE, deviceCode) ?: ""
    deviceName = prefs.getString(EXTRA_DEVICE_NAME, deviceName) ?: ""
    movementConfirmMs = prefs.getLong(EXTRA_MOVEMENT_CONFIRM_MS, movementConfirmMs).coerceIn(0L, 120_000L)
  }

  private fun scheduleMaintenance() {
    maintenanceHandler.postDelayed({
      runMaintenance()
      scheduleMaintenance()
    }, maintenanceInterval)
  }

  private fun runMaintenance() {
    try {
      trackingDb.purge(
        MAX_RECORDS_TO_PERSIST,
        MAX_DAYS_TO_PERSIST * MILLIS_PER_DAY,
        System.currentTimeMillis()
      )
      trackingDb.checkpoint()
    } catch (_: Exception) {
    }
  }

  private fun startForegroundNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Ubicación en segundo plano",
        NotificationManager.IMPORTANCE_LOW
      )
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
    }

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("iSync Tracking")
      .setContentText("Registrando ubicación")
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setColor(android.graphics.Color.parseColor("#1A3D59"))
      .setColorized(true)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun pendingIntentFlags(): Int {
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
    return flags
  }

  private val activityTransitionPendingIntent: PendingIntent by lazy {
    PendingIntent.getBroadcast(this, 0, Intent(this, ActivityTransitionReceiver::class.java), pendingIntentFlags())
  }

  private fun requestActivityTransitionUpdates() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    val transitions = listOf(
      ActivityTransition.Builder()
        .setActivityType(DetectedActivity.STILL)
        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        .build(),
      ActivityTransition.Builder()
        .setActivityType(DetectedActivity.STILL)
        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
        .build()
    )

    try {
      activityRecognitionClient.requestActivityTransitionUpdates(
        ActivityTransitionRequest(transitions),
        activityTransitionPendingIntent
      )
        .addOnSuccessListener { }
        .addOnFailureListener { e -> Log.e(TAG, "Activity Recognition: no se pudo registrar", e) }
    } catch (_: SecurityException) {
    }
  }

  private fun removeActivityTransitionUpdates() {
    try {
      activityRecognitionClient.removeActivityTransitionUpdates(activityTransitionPendingIntent)
    } catch (_: Exception) {
    }
  }

  private fun handleActivityTransition(activityType: Int, transitionType: Int) {
    maintenanceHandler.post {
      if (activityType != DetectedActivity.STILL) return@post

      if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
        armStillTimer()
      } else {
        cancelStillTimer()
        // ActivityRecognition detectó que el usuario ya no está quieto.
        // Encendemos el GPS Espía si aún no está activo.
        if (isStationary && !isTentativeTracking) {
          startTentativeTracking()
        }
      }
    }
  }

  private fun updateStopDetection(location: Location) {
    if (isStationary) return

    val anchor = stopAnchor
    if (anchor == null || anchor.distanceTo(location) > STOP_DETECTION_RADIUS_METERS) {
      stopAnchor = location
      stopAnchorSince = location.time
      return
    }

    val quietMs = location.time - stopAnchorSince
    if (quietMs >= STILL_CONFIRM_MS) {
      enterStationaryMode()
    }
  }

  private fun armStillTimer() {
    cancelStillTimer()
    val runnable = Runnable {
      stillConfirmRunnable = null
      enterStationaryMode()
    }
    stillConfirmRunnable = runnable
    maintenanceHandler.postDelayed(runnable, STILL_CONFIRM_MS)
  }

  private fun cancelStillTimer() {
    stillConfirmRunnable?.let { maintenanceHandler.removeCallbacks(it) }
    stillConfirmRunnable = null
  }

  /**
   * Enciende el GPS en modo espía sin salir del modo estacionario.
   * La decisión de confirmar o descartar el viaje la toma [processLocation]
   * basándose en los puntos reales del GPS. Cero timers implicados.
   */
  private fun startTentativeTracking() {
    if (!isStationary || isTentativeTracking) return
    isTentativeTracking = true
    tentativeTrackingStartedAt = System.currentTimeMillis()
    Log.d(TAG, "GPS Espía: encendido. El GPS decidirá en max 30s.")
    startLocationTracking()
    requestFastFix()
  }

  private fun stopTentativeTracking() {
    if (!isTentativeTracking) return
    isTentativeTracking = false
    if (isStationary) {
      stopLocationTracking()
      Log.d(TAG, "GPS Espía: apagado. Modo estacionario sigue activo. CPU puede dormir.")
    }
  }

  private fun enterStationaryMode() {
    if (isStationary) return
    cancelStillTimer()
    val anchor = stopAnchor ?: lastAccepted ?: return
    isStationary = true
    stopLocationTracking()
    saveStationaryState(anchor)
    // Armar el sensor de hardware. CPU principal duerme al 100%.
    deviceMotionSensor.arm()
    Log.d(TAG, "Modo estacionario: GPS apagado, sensor armado. CPU en reposo.")
  }

  private fun exitStationaryMode() {
    if (!isStationary) return
    val anchor = stopAnchor ?: lastAccepted
    isStationary = false
    isTentativeTracking = false
    saveStationaryState(null)
    stopAnchor = null
    stopAnchorSince = 0L
    cancelStillTimer()
    deviceMotionSensor.disarm()
    if (anchor != null) emitAnchoredExitPoint(anchor)
    startLocationTracking()
    requestFastFix()
  }

  private fun emitAnchoredExitPoint(anchor: Location) {
    val anchorPoint = Location(anchor).apply {
      time = System.currentTimeMillis()
      speed = 0f
      bearing = 0f
    }
    persistAndEmit(anchorPoint)
  }

  private fun requestFastFix() {
    val request = CurrentLocationRequest.Builder()
      .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
      .setMaxUpdateAgeMillis(0)
      .setDurationMillis(5_000)
      .build()

    try {
      fusedClient.getCurrentLocation(request, null)
        .addOnSuccessListener { fastLocation: Location? ->
          if (fastLocation == null) return@addOnSuccessListener
          maintenanceHandler.post {
            if (!isTracking) return@post
            lastFixTimeMs = fastLocation.time
            processLocation(fastLocation)
          }
        }
        .addOnFailureListener { }
    } catch (_: SecurityException) {
    }
  }

  private fun saveStationaryState(anchor: Location?) {
    val editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
    if (anchor == null) {
      editor.putBoolean(PREF_IS_STATIONARY, false)
    } else {
      editor.putBoolean(PREF_IS_STATIONARY, true)
      editor.putLong(PREF_STATIONARY_LAT, java.lang.Double.doubleToRawLongBits(anchor.latitude))
      editor.putLong(PREF_STATIONARY_LNG, java.lang.Double.doubleToRawLongBits(anchor.longitude))
    }
    editor.apply()
  }

  private fun readStationaryAnchor(): Location? {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(PREF_IS_STATIONARY, false)) return null
    if (!prefs.contains(PREF_STATIONARY_LAT) || !prefs.contains(PREF_STATIONARY_LNG)) return null

    return Location("isync_stationary_restored").apply {
      latitude = java.lang.Double.longBitsToDouble(prefs.getLong(PREF_STATIONARY_LAT, 0))
      longitude = java.lang.Double.longBitsToDouble(prefs.getLong(PREF_STATIONARY_LNG, 0))
    }
  }

  companion object {
    private const val TAG = "IsyncBgLocation"

    @Volatile
    var isRunning = false
      private set

    private var instance: WeakReference<IsyncBackgroundLocationService>? = null

    fun onActivityTransition(activityType: Int, transitionType: Int) {
      instance?.get()?.handleActivityTransition(activityType, transitionType)
    }

    fun motionSnapshot(): Map<String, Any?> {
      return instance?.get()?.deviceMotionSensor?.snapshot()
        ?: mapOf("type" to "SignificantMotion", "supported" to false, "armed" to false)
    }
  }
}