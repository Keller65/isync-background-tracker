export type LocationEventPayload = {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude: number;
  speed: number;
  /** Rumbo en grados (0-360). `bearing` en Android. */
  heading: number;
  /** Epoch en milisegundos, tal como lo entrega el proveedor. */
  timestamp: number;
};

export type IsyncBackgroundLocationModuleEvents = {
  location: (params: LocationEventPayload) => void;
  /** Transición dentro/fuera de una geovalla (etapa 3). Solo se emite al cruzar un borde. */
  geovalla: (params: GeoVallaEvent) => void;
};

/** Un vértice del polígono, listo para dibujar. */
export type GeoPoint = { latitude: number; longitude: number };

/**
 * Geovalla poligonal cacheada/local (misma estructura que el `Feature` del API
 * REST de geovallas de `isync-tracker-ws`, RFC 7946 — el ciclo completo lo
 * documenta `docs/Geovallas.md`). El server devuelve los anillos como
 * `[lng, lat]`; el módulo los entrega ya convertidos a
 * `{ latitude, longitude }`.
 */
export type GeoValla = {
  id: number;
  nombre: string;
  descripcion: string | null;
  tipo: string | null;
  activo: boolean;
  /** Anillos del polígono; `rings[0]` es el exterior y va cerrado. */
  rings: GeoPoint[][];
  /** Copia del GeoJSON original (`properties.geojson`), si el server la trajo. */
  geojson: string | null;
  /** Epoch ms del último refresh que bajó la geovalla. */
  updated: number;
};

/** Payload del evento `geovalla`. */
export type GeoVallaEvent = {
  id: number;
  nombre: string;
  tipo: string | null;
  activo: boolean;
  /** true = entró, false = salió. */
  inside: boolean;
  latitude: number;
  longitude: number;
  /** Epoch ms del fix que disparó la transición. */
  timestamp: number;
};

export type BackgroundLocationStatus = {
  /** true en Android, la única plataforma soportada por el módulo. */
  available: boolean;
  /**
   * true mientras la captura esté activa: el Foreground Service en Android.
   * Módulo solo Android.
   */
  isRunning: boolean;
};

export type LocationTrackingOptions = {
  /** Intervalo objetivo entre fixes, en ms. Por defecto 5000. */
  interval?: number;
  /** Desplazamiento mínimo entre fixes, en metros. Por defecto 1 (el piso de ruido espacial es 2 m: fixes más cercanos se descartan). */
  distanceInterval?: number;
  /** Código del vendedor. deviceId real (Android ID) se toma nativamente. */
  deviceCode?: string;
  deviceName?: string;
  /**
   * Tiempo de confirmación del wake por sensores (IMU) antes de reactivar el
   * GPS al salir del reposo, en ms. Default `15000`. Separa un movimiento
   * sostenido de un impulso (p. ej. levantar el teléfono de una mesa): un
   * valor menor despierta más agresivo, con más falsos positivos. Solo Android.
   */
  movementConfirmMs?: number;
};

/** Estado del detector de movimiento por sensores (ver `getMotionState()`). */
export type MotionSensorState = {
  /** true si al menos un sensor de movimiento está activo en el Service. */
  registered: boolean;
  /**
   * true mientras los sensores detecten movimiento (o no haya sensores:
   * en ese caso el gate queda permisivo y el GPS decide solo).
   */
  moving: boolean;
  /** Norma del acelerómetro filtrada (high-pass), en m/s². */
  accelMagnitude: number;
  /** Norma del giroscopio, en rad/s. */
  gyroMagnitude: number;
  /** Delta de magnitud del magnetómetro entre muestras, en µT. */
  magDelta: number;
};

/** Punto guardado en el storage nativo (SQLite propio del módulo, sin datos de usuario). */
export type StoredPoint = {
  id: number;
  /** Clave de idempotencia para el backend, generada al capturar el fix. */
  clientUuid: string;
  latitude: number;
  longitude: number;
  altitude: number | null;
  speed: number | null;
  accuracy: number | null;
  heading: number | null;
  timestamp: number;
  /** 0 = pendiente, 1 = sincronizado. */
  sincronizado?: number;
  /** Cuántos intentos de subida tiene este punto (debug). */
  intentos?: number;
};
