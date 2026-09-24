package expo.modules.isyncbackgroundlocation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeoPoint(val latitude: Double, val longitude: Double)

data class GeoValla(
  val id: Long,
  val nombre: String,
  val descripcion: String?,
  val tipo: String?,
  val activo: Boolean,
  val rings: List<List<GeoPoint>>,
  val geojson: String?,
  val updated: Long = System.currentTimeMillis()
)

fun GeoValla.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "nombre" to nombre,
  "descripcion" to descripcion,
  "tipo" to tipo,
  "activo" to activo,
  "rings" to rings.map { ring ->
    ring.map { mapOf("latitude" to it.latitude, "longitude" to it.longitude) }
  },
  "geojson" to geojson,
  "updated" to updated
)

fun GeoValla.contains(point: GeoPoint): Boolean {
  val ring = rings.firstOrNull() ?: return false
  var inside = false
  var j = ring.size - 1
  for (i in ring.indices) {
    val a = ring[i]
    val b = ring[j]
    if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
      val xIntersect = a.longitude +
        (point.latitude - a.latitude) * (b.longitude - a.longitude) / (b.latitude - a.latitude)
      if (point.longitude < xIntersect) inside = !inside
    }
    j = i
  }
  return inside
}

fun ringsJson(rings: List<List<GeoPoint>>): String {
  val arr = JSONArray()
  for (ring in rings) {
    val r = JSONArray()
    for (p in ring) {
      r.put(JSONArray().put(p.longitude).put(p.latitude))
    }
    arr.put(r)
  }
  return arr.toString()
}

fun parseRingsJson(text: String): List<List<GeoPoint>> = parseRingsArray(JSONArray(text))

fun parseFeatureCollection(json: String): List<GeoValla> {
  val root = JSONObject(json)
  val features = root.optJSONArray("features") ?: return emptyList()

  val out = mutableListOf<GeoValla>()
  for (i in 0 until features.length()) {
    val feature = features.optJSONObject(i) ?: continue
    val geometry = feature.optJSONObject("geometry")
    if (geometry?.optString("type") != "Polygon") continue
    val rings = parseRingsArray(geometry.optJSONArray("coordinates") ?: continue)
    if (rings.isEmpty()) continue

    val properties = feature.optJSONObject("properties") ?: JSONObject()
    val id = if (feature.has("id")) feature.optLong("id") else properties.optLong("id")
    val nombre = properties.optString("nombre")
      .takeIf { it.isNotBlank() }
      ?: properties.optString("name").takeIf { it.isNotBlank() }
      ?: "Geovalla sin nombre"
    val descripcion = properties.optString("descripcion").ifBlank { null }
      ?: properties.optString("description").ifBlank { null }
    val tipo = properties.optString("tipo").ifBlank { null }
      ?: properties.optString("type").ifBlank { null }
    val activo = !properties.has("activo") || properties.optBoolean("activo", true)
    val geojson = if (properties.has("geojson")) properties.getJSONObject("geojson").toString() else null

    out.add(GeoValla(id, nombre, descripcion, tipo, activo, rings, geojson))
  }
  return out
}

private fun parseRingsArray(coords: JSONArray): List<List<GeoPoint>> {
  val rings = mutableListOf<List<GeoPoint>>()
  for (i in 0 until coords.length()) {
    val ring = coords.optJSONArray(i) ?: continue
    if (ring.length() < 4) continue

    var valid = true
    val points = mutableListOf<GeoPoint>()
    for (j in 0 until ring.length()) {
      val pt = ring.optJSONArray(j)
      if (pt == null || pt.length() < 2) {
        valid = false
        break
      }
      points.add(GeoPoint(latitude = pt.getDouble(1), longitude = pt.getDouble(0)))
    }
    if (valid && points.isNotEmpty()) rings.add(points)
  }
  return rings
}

class GeoVallasServices private constructor(private val context: Context) {

  companion object {
    private const val TAG = "IsyncBgLocation"
    private const val BASE_URL = "https://isync-tracker-ws.vercel.app/api/geovallas"

    private val lock = Any()

    @Volatile
    private var instance: GeoVallasServices? = null

    fun getInstance(context: Context): GeoVallasServices =
      instance ?: synchronized(lock) {
        instance ?: GeoVallasServices(context.applicationContext).also { instance = it }
      }
  }

  @Volatile
  var cached: List<GeoValla> = emptyList()
    private set

  private val insideByGeoValla = HashMap<Long, Boolean>()

  @Volatile
  private var workerHandler: Handler? = null

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MINUTES))
    .build()

  fun attach(handler: Handler) {
    workerHandler = handler
  }

  fun boot(): List<GeoValla> {
    val list = TrackingDatabase.getInstance(context).getGeoVallas()
    if (list.isNotEmpty()) cached = list
    return cached
  }

  fun cachedOrBoot(): List<GeoValla> {
    if (cached.isEmpty()) boot()
    return cached
  }

  fun refresh(onDone: (List<GeoValla>) -> Unit = {}, onError: (String?) -> Unit = {}) {
    val request = Request.Builder().url(BASE_URL).get().build()
    httpClient.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.e(TAG, "geovallas: GET falló — $BASE_URL", e)
        onError(e.message)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (it.code != 200) {
            Log.e(TAG, "geovallas: GET HTTP ${it.code}")
            onError("HTTP ${it.code}")
            return
          }
          val body = it.body?.string() ?: return
          try {
            val list = parseFeatureCollection(body)
            postApply(list)
            onDone(list)
          } catch (e: Exception) {
            Log.e(TAG, "geovallas: respuesta inválida", e)
            onError(e.message)
          }
        }
      }
    })
  }

  private fun apply(list: List<GeoValla>) {
    cached = list
    insideByGeoValla.clear()
    TrackingDatabase.getInstance(context).replaceGeoVallas(list)
  }

  private fun postApply(list: List<GeoValla>) {
    val handler = workerHandler
    if (handler != null) {
      handler.post { apply(list) }
    } else {
      apply(list)
    }
  }

  fun evaluate(latitude: Double, longitude: Double, timestamp: Long) {
    val point = GeoPoint(latitude, longitude)
    for (valla in cached) {
      if (!valla.activo || valla.rings.isEmpty()) continue

      val inside = valla.contains(point)
      val observedBefore = insideByGeoValla.containsKey(valla.id)
      val wasInside = insideByGeoValla[valla.id] ?: false

      val emit = if (!observedBefore) {
        insideByGeoValla[valla.id] = inside
        inside
      } else if (wasInside != inside) {
        insideByGeoValla[valla.id] = inside
        true
      } else {
        false
      }

      if (emit) {
        if (inside) GeovallaNotifier.notifyEnter(context, valla)
        IsyncBackgroundLocationModule.emitGeoVallaEvent(
          mapOf(
            "id" to valla.id,
            "nombre" to valla.nombre,
            "tipo" to valla.tipo,
            "activo" to valla.activo,
            "inside" to inside,
            "latitude" to latitude,
            "longitude" to longitude,
            "timestamp" to timestamp
          )
        )
      }
    }
  }

  fun release() {
    httpClient.dispatcher.executorService.shutdown()
    httpClient.connectionPool.evictAll()
    synchronized(lock) {
      if (instance === this) instance = null
      insideByGeoValla.clear()
    }
  }
}

private object GeovallaNotifier {
  private const val CHANNEL_ID = "geovalla_events"
  private const val CHANNEL_NAME = "Geovallas"
  private const val CHANNEL_DESC = "Aviso al entrar en una geovalla"

  fun notifyEnter(context: Context, valla: GeoValla) {
    if (!enabled(context)) return
    if (!canPost(context)) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel(context))
    manager.notify(valla.id.toInt(), notification(context, valla))
  }

  private fun notification(context: Context, valla: GeoValla) =
    NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentTitle("Geovalla")
      .setContentText("Entraste a ${valla.nombre}")
      .setContentIntent(launcherPendingIntent(context))
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .build()

  private fun launcherPendingIntent(context: Context): PendingIntent {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    return PendingIntent.getActivity(context, 0, intent, flags())
  }

  private fun channel(context: Context): NotificationChannel {
    val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.location}")
    val attrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_NOTIFICATION)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
    return NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
      description = CHANNEL_DESC
      setSound(soundUri, attrs)
      enableVibration(true)
    }
  }

  private fun canPost(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun enabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(PREF_GEOVALLA_NOTIFICATIONS, true)

  private fun flags(): Int {
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
    return flags
  }
}