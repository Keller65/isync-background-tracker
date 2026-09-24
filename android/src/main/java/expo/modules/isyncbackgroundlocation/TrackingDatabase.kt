package expo.modules.isyncbackgroundlocation

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class TrackingDatabase private constructor(context: Context) :
  SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

  override fun onConfigure(db: SQLiteDatabase) {
    db.enableWriteAheadLogging()
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE $TABLE (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        clientUuid TEXT NOT NULL,
        latitude   REAL NOT NULL,
        longitude  REAL NOT NULL,
        altitude   REAL,
        speed      REAL,
        accuracy   REAL,
        heading    REAL,
        timestamp  INTEGER NOT NULL,
        sincronizado  INTEGER DEFAULT 0,
        intentos  INTEGER DEFAULT 0
      )
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX idx_${TABLE}_timestamp ON $TABLE(timestamp)")
    db.execSQL(GEO_TABLE_DDL)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE $TABLE ADD COLUMN sincronizado INTEGER DEFAULT 0")
      db.execSQL("ALTER TABLE $TABLE ADD COLUMN intentos INTEGER DEFAULT 0")
    }
    if (oldVersion < 4) {
      db.execSQL(GEO_TABLE_DDL)
    }
  }

  fun insert(location: android.location.Location): String? {
    val clientUuid = UUID.randomUUID().toString()
    val values = ContentValues().apply {
      put("clientUuid", clientUuid)
      put("latitude", location.latitude)
      put("longitude", location.longitude)
      put("altitude", if (location.hasAltitude()) location.altitude else null)
      put("speed", if (location.hasSpeed()) location.speed else null)
      put("accuracy", if (location.hasAccuracy()) location.accuracy else null)
      put("heading", if (location.hasBearing()) location.bearing else null)
      put("timestamp", location.time)
      put("sincronizado", 0)
      put("intentos", 0)
    }
    val rowId = writableDatabase.insert(TABLE, null, values)
    return if (rowId != -1L) clientUuid else null
  }

  /** Para el puente JS (getPendingPoints): serializa a Map nativo. */
  fun getPending(limit: Int): List<Map<String, Any?>> {
    val out = mutableListOf<Map<String, Any?>>()
    readableDatabase.rawQuery(
      "SELECT id, clientUuid, latitude, longitude, altitude, speed, accuracy, heading, timestamp " +
        "FROM $TABLE ORDER BY timestamp ASC LIMIT ?",
      arrayOf(limit.toString())
    ).use { cursor ->
      while (cursor.moveToNext()) {
        out.add(
          mapOf(
            "id" to cursor.getLong(0),
            "clientUuid" to cursor.getString(1),
            "latitude" to cursor.getDouble(2),
            "longitude" to cursor.getDouble(3),
            "altitude" to if (cursor.isNull(4)) null else cursor.getDouble(4),
            "speed" to if (cursor.isNull(5)) null else cursor.getDouble(5),
            "accuracy" to if (cursor.isNull(6)) null else cursor.getDouble(6),
            "heading" to if (cursor.isNull(7)) null else cursor.getDouble(7),
            "timestamp" to cursor.getLong(8)
          )
        )
      }
    }
    return out
  }

  /** Para el batch sync nativo: construcción al vuelo. Itera el Cursor
   *  dentro de un bloque `use` (máx. [limit] por el LIMIT de la SQL) y le
   *  pasa la fila RAW a la callback. Los primitivos y nullables se leen
   *  ahí mismo y se inyectan directo al Builder de protobuf — no se
   *  materializa ninguna data class ni lista en RAM.
   *
   *  Columnas (índices): 0=id, 1=clientUuid, 2=latitude, 3=longitude,
   *  4=altitude, 5=speed, 6=accuracy, 7=heading, 8=timestamp. */
  fun forEachRow(limit: Int, onRow: (Cursor) -> Unit) {
    readableDatabase.rawQuery(
      "SELECT id, clientUuid, latitude, longitude, altitude, speed, accuracy, heading, timestamp " +
        "FROM $TABLE ORDER BY timestamp ASC LIMIT ?",
      arrayOf(limit.toString())
    ).use { cursor ->
      while (cursor.moveToNext()) {
        onRow(cursor)
      }
    }
  }

  fun count(): Int {
    readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
      cursor.moveToFirst()
      return cursor.getInt(0)
    }
  }

  fun purge(maxRecords: Int, maxAgeMillis: Long, nowMillis: Long): Int {
    var deleted = 0

    val newest = if (maxAgeMillis > 0) newestTimestamp() else null
    if (newest != null) {
      deleted += deleteInChunks(
        "DELETE FROM $TABLE WHERE id IN (" +
          "SELECT id FROM $TABLE WHERE timestamp < ? ORDER BY timestamp ASC LIMIT $PURGE_CHUNK_SIZE" +
          ")",
        minOf(nowMillis, newest) - maxAgeMillis
      )
    }

    if (maxRecords > 0) {
      deleted += deleteInChunks(
        "DELETE FROM $TABLE WHERE id IN (" +
          "SELECT id FROM $TABLE ORDER BY timestamp DESC LIMIT $PURGE_CHUNK_SIZE OFFSET ?" +
          ")",
        maxRecords.toLong()
      )
    }

    return deleted
  }

  private fun newestTimestamp(): Long? {
    readableDatabase.rawQuery("SELECT MAX(timestamp) FROM $TABLE", null).use { cursor ->
      if (!cursor.moveToFirst() || cursor.isNull(0)) return null
      return cursor.getLong(0)
    }
  }

  private fun deleteInChunks(sql: String, arg: Long): Int {
    var total = 0
    writableDatabase.compileStatement(sql).use { stmt ->
      repeat(PURGE_MAX_CHUNKS_PER_PASS) {
        stmt.bindLong(1, arg)
        val affected = stmt.executeUpdateDelete()
        if (affected == 0) return total
        total += affected
      }
    }
    return total
  }

  fun deleteByIds(ids: List<Long>) {
    if (ids.isEmpty()) return
    val placeholders = ids.joinToString(",") { "?" }
    writableDatabase.execSQL(
      "DELETE FROM $TABLE WHERE id IN ($placeholders)",
      ids.map { it.toString() }.toTypedArray()
    )
  }

  /** Marca los puntos como sincronizados (200 confirmado del server). */
  fun markSynced(ids: List<Long>) {
    if (ids.isEmpty()) return
    val placeholders = ids.joinToString(",") { "?" }
    writableDatabase.execSQL(
      "UPDATE $TABLE SET sincronizado = 1 WHERE id IN ($placeholders)",
      ids.map { it.toString() }.toTypedArray()
    )
  }

  /** Incrementa el contador de intentos para depuración. */
  fun incrementAttempts(ids: List<Long>) {
    if (ids.isEmpty()) return
    val placeholders = ids.joinToString(",") { "?" }
    writableDatabase.execSQL(
      "UPDATE $TABLE SET intentos = intentos + 1 WHERE id IN ($placeholders)",
      ids.map { it.toString() }.toTypedArray()
    )
  }

  fun closeInstance() {
    synchronized(Companion) {
      super.close()
      instance = null
    }
  }

  // ── Geovallas (etapa 3): cache local del FeatureCollection de /api/geovallas ──
  // La lista es autoritativa (máx. 500, id DESC) — cada GET exitoso reemplaza
  // todo el contenido. Los rings viajan como JSON `[[[lng, lat], ...], ...]`,
  // la forma canónica del contrato.

  fun replaceGeoVallas(list: List<GeoValla>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.delete(GEO_TABLE, null, null)
      for (v in list) {
        db.insertOrThrow(GEO_TABLE, null, ContentValues().apply {
          put("id", v.id)
          put("nombre", v.nombre)
          put("descripcion", v.descripcion)
          put("tipo", v.tipo)
          put("activo", if (v.activo) 1 else 0)
          put("rings", ringsJson(v.rings))
          put("geojson", v.geojson)
          put("updated", v.updated)
        })
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun getGeoVallas(): List<GeoValla> {
    val out = mutableListOf<GeoValla>()
    readableDatabase.rawQuery(
      "SELECT id, nombre, descripcion, tipo, activo, rings, geojson, updated FROM $GEO_TABLE ORDER BY id DESC",
      null
    ).use { cursor ->
      while (cursor.moveToNext()) {
        out.add(
          GeoValla(
            id = cursor.getLong(0),
            nombre = cursor.getString(1) ?: "Geovalla sin nombre",
            descripcion = cursor.getString(2),
            tipo = cursor.getString(3),
            activo = cursor.getInt(4) == 1,
            rings = parseRingsJson(cursor.getString(5) ?: continue),
            geojson = cursor.getString(6),
            updated = cursor.getLong(7)
          )
        )
      }
    }
    return out
  }

  fun countGeoVallas(): Int {
    readableDatabase.rawQuery("SELECT COUNT(*) FROM $GEO_TABLE", null).use { cursor ->
      cursor.moveToFirst()
      return cursor.getInt(0)
    }
  }

  fun checkpoint() {
    try {
      readableDatabase.rawQuery("PRAGMA wal_checkpoint(RESTART)", null).close()
    } catch (_: Exception) {
    }
  }

  companion object {
    private const val DB_NAME = "isync_tracking.db"
    private const val DB_VERSION = 4
    private const val TABLE = "location_points"
    private const val GEO_TABLE = "geovallas"
    private val GEO_TABLE_DDL =
      """
      CREATE TABLE $GEO_TABLE (
        id          INTEGER PRIMARY KEY,
        nombre      TEXT NOT NULL,
        descripcion TEXT,
        tipo        TEXT,
        activo      INTEGER NOT NULL DEFAULT 1,
        rings       TEXT NOT NULL,
        geojson     TEXT,
        updated     INTEGER NOT NULL
      )
      """.trimIndent()

    private const val PURGE_CHUNK_SIZE = 5_000
    private const val PURGE_MAX_CHUNKS_PER_PASS = 100

    @Volatile
    private var instance: TrackingDatabase? = null

    fun getInstance(context: Context): TrackingDatabase =
      instance ?: synchronized(this) {
        instance ?: TrackingDatabase(context).also { instance = it }
      }
  }
}