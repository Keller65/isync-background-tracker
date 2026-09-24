package expo.modules.isyncbackgroundlocation

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Worker durable que drena la cola de puntos pendientes por
 * HTTP POST /gps/batch. Supervive al kill del proceso y al
 * reinicio del dispositivo (reagendado por WorkManager).
 *
 * Una sola iteración:
 *   - Empty / Synced → éxito, sin más trabajo.
 *   - Retryable    → Result.retry() (backoff exponencial desde 30 s,
 *                    gestión propia de WorkManager).
 *   - Fatal        → éxito (no martillar; los puntos quedan en local
 *                    para revisión manual).
 *
 * Si tras un [BatchSyncResult.Synced] quedan más puntos pendientes,
 * se agenda otro worker inmediatamente para seguir drenando.
 */
class BatchSyncWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val sync = LocationBatchSync.getInstance(applicationContext)
    val db = TrackingDatabase.getInstance(applicationContext)

    val result = sync.sync(applicationContext)

    return when (result) {
      BatchSyncResult.Empty -> {
        Result.success()
      }
      is BatchSyncResult.Synced -> {
        val pending = db.count()
        if (pending > 0) {
          scheduleNext(applicationContext)
        }
        Result.success()
      }
      BatchSyncResult.Retryable -> {
        if (runAttemptCount >= MAX_RETRIES) {
          Log.e(TAG, "batch: agotados $MAX_RETRIES reintentos")
          Result.success()
        } else {
          Result.retry()
        }
      }
      BatchSyncResult.Fatal -> {
        Log.e(TAG, "batch: fatal — servidor rechazó, puntos sin borrar")
        Result.success()
      }
    }
  }

  companion object {
    private const val TAG = "IsyncBgLocation"
    private const val WORK_TAG = "batch_sync"
    private const val WORK_TAG_PERIODIC = "batch_sync_periodic"
    private const val MAX_RETRIES = 5
    private const val PERIODIC_INTERVAL_MINUTES = 10L

    /**
     * Worker periódico que corre SIEMPRE, independiente del Service.
     * WorkManager lo re-lanza automáticamente después de cada ejecución.
     * Se agenda una sola vez (al iniciar el Service o la app) y sobrevive
     * kill del proceso y reboot del dispositivo.
     */
    fun schedulePeriodic(context: Context) {
      val constraints = androidx.work.Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
        .build()

      val request = androidx.work.PeriodicWorkRequestBuilder<BatchSyncWorker>(
        PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES
      )
        .addTag(WORK_TAG_PERIODIC)
        .setConstraints(constraints)
        .build()

      androidx.work.WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
          WORK_TAG_PERIODIC,
          androidx.work.ExistingPeriodicWorkPolicy.KEEP,
          request,
        )
    }

    /**
     * Agenda un intento inmediato (one-shot). Usado para el chain
     * cuando hay puntos pendientes después de un sync exitoso.
     */
    fun schedule(context: Context) {
      val db = TrackingDatabase.getInstance(context)
      val pending = db.count()
      if (pending <= 0) return

      val constraints = androidx.work.Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
        .build()

      val request = androidx.work.OneTimeWorkRequestBuilder<BatchSyncWorker>()
        .addTag(WORK_TAG)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

      androidx.work.WorkManager.getInstance(context)
        .beginUniqueWork(
          WORK_TAG,
          androidx.work.ExistingWorkPolicy.REPLACE,
          request,
        )
        .enqueue()
    }

    /** Cancela cualquier sync pendiente (debug/UI). */
    fun cancel(context: Context) {
      androidx.work.WorkManager.getInstance(context)
        .cancelAllWorkByTag(WORK_TAG)
    }

    /** Cancela el worker periódico. */
    fun cancelPeriodic(context: Context) {
      androidx.work.WorkManager.getInstance(context)
        .cancelAllWorkByTag(WORK_TAG_PERIODIC)
    }

    /** Cancela y reagenda (tras cambios de configuración o forzados manualmente). */
    fun cancelAndReschedule(context: Context) {
      cancel(context)
      schedule(context)
    }

    private fun scheduleNext(context: Context) {
      schedule(context)
    }
  }
}
