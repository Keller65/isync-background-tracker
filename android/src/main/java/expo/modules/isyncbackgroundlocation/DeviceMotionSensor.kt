package expo.modules.isyncbackgroundlocation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.util.Log

/**
 * Detector de movimiento significativo basado en hardware puro.
 *
 * Usa TYPE_SIGNIFICANT_MOTION: un sensor de disparo único que corre en el
 * Sensor Hub (microprocesador dedicado de microamperios). El CPU principal
 * permanece completamente dormido hasta que el hardware detecta que el usuario
 * empezó a caminar o a manejar. No realiza polling de ningún tipo.
 *
 * Ciclo de vida:
 *   arm()   → registra el trigger. CPU duerme al 100%.
 *   onTrigger (hardware) → llama a onMotionDetected. El sensor se desactiva solo.
 *   arm()   → debe llamarse de nuevo manualmente para re-armar el sensor.
 *   disarm() → cancela el trigger si ya no es necesario.
 *
 * Fallback: Si el hardware no soporta TYPE_SIGNIFICANT_MOTION (teléfonos muy
 * económicos), [isSupported] será false y el servicio debe depender únicamente
 * de ActivityRecognition como disparador (solo con el proceso vivo).
 */
internal class DeviceMotionSensor {

  companion object {
    private const val TAG = "IsyncBgLocation"
  }

  private var sensorManager: SensorManager? = null
  private var significantMotionSensor: Sensor? = null
  private var handler: Handler? = null
  private var armed = false

  /** Será true si el hardware del dispositivo soporta Significant Motion. */
  var isSupported = false
    private set

  /** true si el trigger está actualmente registrado (armado) en el hardware. */
  val isArmed: Boolean
    get() = armed

  /** Callback que se dispara una sola vez cuando el hardware detecta movimiento real. */
  var onMotionDetected: (() -> Unit)? = null

  private val triggerListener = object : TriggerEventListener() {
    override fun onTrigger(event: TriggerEvent) {
      // El sensor se desactiva solo tras dispararse (diseño de Android).
      // Pasamos al hilo de trabajo del servicio para evitar condiciones de carrera.
      armed = false
      handler?.post {
        Log.d(TAG, "SignificantMotion: trigger de hardware recibido")
        onMotionDetected?.invoke()
      }
    }
  }

  fun initialize(context: Context, handler: Handler) {
    this.handler = handler
    val mgr = context.applicationContext
      .getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
    sensorManager = mgr
    significantMotionSensor = mgr.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    isSupported = significantMotionSensor != null
    if (!isSupported) {
      Log.w(TAG, "SignificantMotion: hardware no disponible en este dispositivo. " +
        "Usando ActivityRecognition como único disparador.")
    }
  }

  /**
   * Arma el sensor. El CPU duerme hasta que el hardware detecte movimiento.
   * No hace nada si ya está armado o si el hardware no está soportado.
   */
  fun arm() {
    if (armed || !isSupported) return
    val mgr = sensorManager ?: return
    val sensor = significantMotionSensor ?: return
    val success = mgr.requestTriggerSensor(triggerListener, sensor)
    if (success) {
      armed = true
      Log.d(TAG, "SignificantMotion: armado. CPU principal libre para dormir.")
    }
  }

  /**
   * Desactiva el sensor. Llamar cuando el GPS ya confirmó el viaje y el
   * sensor ya no necesita disparar.
   */
  fun disarm() {
    if (!armed) return
    val mgr = sensorManager ?: return
    val sensor = significantMotionSensor ?: return
    mgr.cancelTriggerSensor(triggerListener, sensor)
    armed = false
    Log.d(TAG, "SignificantMotion: desarmado manualmente.")
  }

  fun release() {
    disarm()
    onMotionDetected = null
    sensorManager = null
    significantMotionSensor = null
    handler = null
  }

  fun snapshot(): Map<String, Any?> {
    val registered = isSupported && armed
    return mapOf(
      "type" to "SignificantMotion",
      "supported" to isSupported,
      "armed" to armed,
      "registered" to registered,
      "accelMagnitude" to 0.0,
      "gyroMagnitude" to 0.0,
      "magDelta" to 0.0
    )
  }
}