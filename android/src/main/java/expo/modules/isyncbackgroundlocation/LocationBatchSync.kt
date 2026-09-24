package expo.modules.isyncbackgroundlocation

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Canal de respaldo: sube lotes de puntos por HTTP POST /gps/batch
 * (protobuf binario). Cada lote máximo [BATCH_LIMIT] puntos, timestamp ASC.
 *
 * El drenado durable (reintento con backoff, supervivencia al kill del
 * proceso) lo gestiona [BatchSyncWorker] — este clase solo hace una
 * iteración: leer [BATCH_LIMIT] puntos, subir, borrar los confirmados.
 *
 * Reglas de canal de respaldo (ver `docs/BatchSync.md`):
 *   - Headers de identidad sin bearer token.
 *   - Solo se reintentan 429/5xx/red; 400/401/422 son fijos (no martillar).
 *   - El 200 cuenta como aceptado o duplicado — ambos se borran de la
 *     tabla local (el server ya lo tiene).
 */
class LocationBatchSync private constructor(private val context: Context) {

  companion object {
    private const val TAG = "IsyncBgLocation"
    private const val BATCH_URL = "https://isync-tracker-ws.vercel.app/gps/batch"
    private const val BATCH_LIMIT = 50

    @Volatile
    private var instance: LocationBatchSync? = null

    fun getInstance(context: Context): LocationBatchSync =
      instance ?: synchronized(this) {
        instance ?: LocationBatchSync(context.applicationContext).also { instance = it }
      }
  }

  private val prefs: SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MINUTES))
    .build()

  private val deviceId: String
    get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
      ?: "UNKNOWN_DEVICE"

  private val deviceCode: String
    get() = prefs.getString(EXTRA_DEVICE_CODE, "") ?: ""

  private val deviceName: String
    get() = prefs.getString(EXTRA_DEVICE_NAME, "") ?: ""

    /**
     * Sube un lote de hasta [BATCH_LIMIT] puntos pendientes.
     *
     * @return [BatchSyncResult.Empty] si no hay puntos pendientes.
     *         [BatchSyncResult.Synced] si el lote se confirmó (puntos marcados sincronizados).
     *         [BatchSyncResult.Retryable] si hay que reintentar (429/5xx/red).
     *         [BatchSyncResult.Fatal] si el lote fue rechazado por contrato (400/401/422).
     */
    fun sync(context: Context): BatchSyncResult {
      val db = TrackingDatabase.getInstance(context)
      val ids = mutableListOf<Long>()
      val batch = LocationProto.LocationBatch.newBuilder()

      db.forEachRow(BATCH_LIMIT) { row ->
        ids.add(row.getLong(0))
        batch.addPoints(
          LocationProto.LocationPoint.newBuilder()
            .setClientUuid(row.getString(1))
            .setLatitude(row.getDouble(2))
            .setLongitude(row.getDouble(3))
            .setDeviceId(deviceId)
            .setDeviceCode(deviceCode)
            .setDeviceName(deviceName)
            .setTimestamp(row.getLong(8))
            .apply {
              if (!row.isNull(4)) setAltitude(row.getDouble(4))
              if (!row.isNull(6)) setAccuracy(row.getDouble(6).toFloat())
              if (!row.isNull(5)) setSpeed(row.getDouble(5).toFloat())
              if (!row.isNull(7)) setHeading(row.getDouble(7).toFloat())
            }
            .build()
        )
      }

      if (ids.isEmpty()) {
        return BatchSyncResult.Empty
      }

      db.incrementAttempts(ids)

      val batchBody = batch.build().toByteArray()

      val result = when (postBatch(batchBody)) {
        HttpResult.Ok -> {
          db.deleteByIds(ids)
          Log.e(TAG, "batch: 200 OK — ${ids.size} puntos sync+borrados")
          BatchSyncResult.Synced(ids.size)
        }
        HttpResult.Retryable -> {
          Log.e(TAG, "batch: reintentable (429/5xx/red) — ${ids.size} puntos pendientes")
          BatchSyncResult.Retryable
        }
        HttpResult.Fatal -> {
          Log.e(TAG, "batch: fatal (400/401/422) — ${ids.size} puntos sin borrar")
          BatchSyncResult.Fatal
        }
      }

      return result
    }

  private fun postBatch(body: ByteArray): HttpResult {
    val requestBody = RequestBody.create("application/x-protobuf".toMediaTypeOrNull(), body)
    val request = Request.Builder()
      .url(BATCH_URL)
      .addHeader("Content-Type", "application/x-protobuf")
      .addHeader("X-Device-Id", deviceId)
      .addHeader("X-Device-Code", deviceCode)
      .addHeader("X-Device-Name", deviceName)
      .post(requestBody)
      .build()

    return try {
      val response: Response = httpClient.newCall(request).execute()
      response.use {
        when (it.code) {
          200 -> HttpResult.Ok
          400, 401, 422 -> HttpResult.Fatal
          else -> HttpResult.Retryable
        }
      }
    } catch (_: IOException) {
      HttpResult.Retryable
    }
  }

  fun release() {
    httpClient.dispatcher.executorService.shutdown()
    httpClient.connectionPool.evictAll()
    instance = null
  }

  private sealed class HttpResult {
    object Ok : HttpResult()
    object Retryable : HttpResult()
    object Fatal : HttpResult()
  }
}

sealed class BatchSyncResult {
  object Empty : BatchSyncResult()
  data class Synced(val count: Int) : BatchSyncResult()
  object Retryable : BatchSyncResult()
  object Fatal : BatchSyncResult()
}
