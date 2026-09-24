import type { EventSubscription, PermissionResponse } from 'expo-modules-core';

import IsyncBackgroundLocationModule from './IsyncBackgroundLocationModule';
import type {
  BackgroundLocationStatus,
  GeoPoint,
  GeoValla,
  GeoVallaEvent,
  LocationEventPayload,
  LocationTrackingOptions,
  MotionSensorState,
  StoredPoint,
} from './IsyncBackgroundLocation.types';

export type {
  BackgroundLocationStatus,
  GeoPoint,
  GeoValla,
  GeoVallaEvent,
  LocationEventPayload,
  LocationTrackingOptions,
  MotionSensorState,
  StoredPoint,
};

export const BackgroundLocation = {
  /**
   * Pide los permisos de ubicación.
   * Android: FINE + COARSE + POST_NOTIFICATIONS (foreground).
   * iOS: When In Use y luego el ascenso a Always, que es lo que habilita la
   * captura en segundo plano.
   */
  requestPermissions(): Promise<PermissionResponse> {
    return IsyncBackgroundLocationModule.requestPermissions();
  },

  /**
   * Arranca el tracking. Persiste en SQLite nativo, sin depender de JS.
   * Android: Foreground Service. iOS: CLLocationManager en background.
   */
  start(options: LocationTrackingOptions = {}): Promise<null> {
    return IsyncBackgroundLocationModule.start(options);
  },

  /** Detiene el tracking. */
  stop(): Promise<null> {
    return IsyncBackgroundLocationModule.stop();
  },

  getStatus(): Promise<BackgroundLocationStatus> {
    return IsyncBackgroundLocationModule.getStatus();
  },

  /**
   * Estado del detector de movimiento por sensores (debug/UI): si los
   * sensores están registrados y si el dispositivo se está moviendo. Este
   * estado alimenta el gate de captura: con el device quieto, los fixes del
   * GPS se descartan sin persistir.
   */
  getMotionState(): Promise<MotionSensorState> {
    return IsyncBackgroundLocationModule.getMotionState();
  },

  /** Un fix puntual, sin arrancar el tracking. */
  getCurrentLocation(): Promise<LocationEventPayload> {
    return IsyncBackgroundLocationModule.getCurrentLocation();
  },

  /** Evento best-effort para UI en vivo. No es la vía de persistencia — eso es el storage nativo. */
  addLocationListener(listener: (event: LocationEventPayload) => void): EventSubscription {
    return IsyncBackgroundLocationModule.addListener('location', listener);
  },

  /** Transiciones dentro/fuera de las geovallas (etapa 3). Solo fire al cruzar un borde. */
  addGeoVallasListener(listener: (event: GeoVallaEvent) => void): EventSubscription {
    return IsyncBackgroundLocationModule.addListener('geovalla', listener);
  },

  /**
   * Geovallas en cache local (tabla SQLite nativa). El módulo las baja una
   * sola vez al arrancar el Service (sin refresco periódico). Los anillos
   * vienen en `{latitude, longitude}` listos para dibujar (rings[0] = exterior).
   */
  getGeoVallas(): Promise<GeoValla[]> {
    return IsyncBackgroundLocationModule.getGeoVallas();
  },

  /**
   * Descarga de nuevo las geovallas del servidor (GET /api/geovallas) y
   * reemplaza la cache en memoria y la tabla SQLite. Útil cuando se agregan o
   * editan geovallas en el backend: el arranque del Service es el único otro
   * momento de descarga. Resuelve con la lista recién descargada.
   */
  refreshGeoVallas(): Promise<GeoValla[]> {
    return IsyncBackgroundLocationModule.refreshGeoVallas();
  },

  /**
   * Activa/desactiva la notificación heads-up al ENTRAR en una geovalla.
   * Persistido en prefs nativas (`geovalla_notifications`) y evaluado por el
   * notifier en el workerLooper — no depende de que la UI esté viva.
   */
  setGeovallaNotificationsEnabled(enabled: boolean): Promise<null> {
    return IsyncBackgroundLocationModule.setGeovallaNotificationsEnabled(enabled);
  },

  /** Lee el flag nativo de notificaciones de geovallas (default: true). */
  getGeovallaNotificationsEnabled(): Promise<boolean> {
    return IsyncBackgroundLocationModule.getGeovallaNotificationsEnabled();
  },

  /** Lee puntos pendientes del storage nativo (más antiguos primero), sin borrarlos. */
  getPendingPoints(limit = 100): Promise<StoredPoint[]> {
    return IsyncBackgroundLocationModule.getPendingPoints(limit);
  },

  getPendingPointsCount(): Promise<number> {
    return IsyncBackgroundLocationModule.getPendingPointsCount();
  },

  /** Borra puntos del storage nativo (uso manual/debug). */
  deletePoints(ids: number[]): Promise<null> {
    return IsyncBackgroundLocationModule.deletePoints(ids);
  },

  /**
   * Reanuda el drenado de la cola de puntos pendientes:
   * POST /gps/batch (protobuf) vía WorkManager. Reintenta con
   * backoff exponencial en 429/5xx/red; no reintenta en 400/401/422.
   * Idempotente — un único worker en cola por tag.
   */
  startBatchSync(): Promise<null> {
    return IsyncBackgroundLocationModule.startBatchSync();
  },

  /** Cancela el drenado pendiente (debug/control). */
  stopBatchSync(): Promise<null> {
    return IsyncBackgroundLocationModule.stopBatchSync();
  },
};
