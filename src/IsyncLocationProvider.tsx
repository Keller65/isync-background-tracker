import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import type { PermissionResponse, Subscription } from 'expo-modules-core';

import IsyncBackgroundLocationModule from './IsyncBackgroundLocationModule';
import { providerGate } from './ProviderGate';
import type {
  BackgroundLocationStatus,
  GeoValla,
  GeoVallaEvent,
  LocationEventPayload,
  LocationTrackingOptions,
  MotionSensorState,
} from './IsyncBackgroundLocation.types';

export const MAX_GEOVALLA_TRANSITIONS = 50;

export type IsyncLocationProviderProps = {
  children?: ReactNode;
  /** Opciones aplicadas a `start()` cuando `autoStart` es true. */
  options?: LocationTrackingOptions;
  /**
   * true: al montar pide permisos y arranca el tracking solo.
   * Usa false para gatearlo detrás de un onboarding y llamar
   * a `start()`/`requestPermissions()` manualmente. Default true.
   */
  autoStart?: boolean;
  /** Período de refresco de contadores (colas/sensores) en ms. 0 lo desactiva. Default 10000. */
  pollIntervalMs?: number;
  /** Callback por cada fix aceptado (equivalente a addLocationListener). */
  onLocation?: (point: LocationEventPayload) => void;
  /** Callback por cada transición de geovalla (equivalente a addGeoVallasListener). */
  onGeoValla?: (event: GeoVallaEvent) => void;
  /** Callback ante cambios de `isRunning`. */
  onStatusChange?: (status: BackgroundLocationStatus) => void;
  /** Callback ante cualquier error del módulo. */
  onError?: (error: Error) => void;
};

export type IsyncLocationContextValue = {
  available: boolean;
  isRunning: boolean;
  /** Último fix aceptado por el pipeline (null hasta el primer fix). */
  lastLocation: LocationEventPayload | null;
  /** Geovallas en cache local. */
  geoVallas: GeoValla[];
  /** Transiciones de geovalla recientes (más reciente primero, cap: MAX_GEOVALLA_TRANSITIONS). */
  geovallaTransitions: GeoVallaEvent[];
  motionState: MotionSensorState | null;
  pendingCount: number;
  notificationsEnabled: boolean;
  requestingPermissions: boolean;
  /** Último error del módulo (null si todo va bien). */
  error: Error | null;
  requestPermissions: () => Promise<PermissionResponse>;
  start: (options?: LocationTrackingOptions) => Promise<void>;
  stop: () => Promise<void>;
  refreshGeoVallas: () => Promise<void>;
  refreshPendingCount: () => Promise<void>;
  setGeovallaNotificationsEnabled: (enabled: boolean) => Promise<void>;
  /** Reanuda el drenado de la cola pendiente (WorkManager). */
  syncNow: () => Promise<void>;
  deletePoints: (ids: number[]) => Promise<void>;
};

const IsyncLocationContext = createContext<IsyncLocationContextValue | null>(null);

export function IsyncLocationProvider({
  children,
  options,
  autoStart = true,
  pollIntervalMs = 10_000,
  onLocation,
  onGeoValla,
  onStatusChange,
  onError,
}: IsyncLocationProviderProps) {
  const [status, setStatus] = useState<BackgroundLocationStatus>({
    available: true,
    isRunning: false,
  });
  const [lastLocation, setLastLocation] = useState<LocationEventPayload | null>(null);
  const [geoVallas, setGeoVallas] = useState<GeoValla[]>([]);
  const [geovallaTransitions, setGeovallaTransitions] = useState<GeoVallaEvent[]>([]);
  const [motionState, setMotionState] = useState<MotionSensorState | null>(null);
  const [pendingCount, setPendingCount] = useState(0);
  const [notificationsEnabled, setNotificationsEnabled] = useState(true);
  const [requestingPermissions, setRequestingPermissions] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const optionsRef = useRef(options);
  optionsRef.current = options;
  const onLocationRef = useRef(onLocation);
  onLocationRef.current = onLocation;
  const onGeoVallaRef = useRef(onGeoValla);
  onGeoVallaRef.current = onGeoValla;
  const onStatusChangeRef = useRef(onStatusChange);
  onStatusChangeRef.current = onStatusChange;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  const fail = useCallback((err: unknown) => {
    const e = err instanceof Error ? err : new Error(String(err));
    setError(e);
    onErrorRef.current?.(e);
    return e;
  }, []);

  const refreshState = useCallback(async () => {
    try {
      const [st, gv, notif, ms, pc] = await Promise.all([
        IsyncBackgroundLocationModule.getStatus(),
        IsyncBackgroundLocationModule.getGeoVallas(),
        IsyncBackgroundLocationModule.getGeovallaNotificationsEnabled(),
        IsyncBackgroundLocationModule.getMotionState(),
        IsyncBackgroundLocationModule.getPendingPointsCount(),
      ]);
      setStatus(st);
      setGeoVallas(gv);
      setNotificationsEnabled(notif);
      setMotionState(ms);
      setPendingCount(pc);
    } catch (e) {
      fail(e);
    }
  }, [fail]);

  const refreshPendingCount = useCallback(async () => {
    try {
      setPendingCount(await IsyncBackgroundLocationModule.getPendingPointsCount());
    } catch (e) {
      fail(e);
    }
  }, [fail]);

  const refreshMotionState = useCallback(async () => {
    try {
      setMotionState(await IsyncBackgroundLocationModule.getMotionState());
    } catch (e) {
      fail(e);
    }
  }, [fail]);

  const requestPermissions = useCallback(async () => {
    setRequestingPermissions(true);
    setError(null);
    try {
      return await IsyncBackgroundLocationModule.requestPermissions();
    } catch (e) {
      throw fail(e);
    } finally {
      setRequestingPermissions(false);
    }
  }, [fail]);

  const start = useCallback(
    async (opts?: LocationTrackingOptions) => {
      try {
        await IsyncBackgroundLocationModule.start({ ...optionsRef.current, ...opts });
        await refreshState();
      } catch (e) {
        throw fail(e);
      }
    },
    [refreshState, fail],
  );

  const stop = useCallback(async () => {
    try {
      await IsyncBackgroundLocationModule.stop();
      await refreshState();
    } catch (e) {
      throw fail(e);
    }
  }, [refreshState, fail]);

  const refreshGeoVallas = useCallback(async () => {
    try {
      setGeoVallas(await IsyncBackgroundLocationModule.refreshGeoVallas());
    } catch (e) {
      throw fail(e);
    }
  }, [fail]);

  const setGeovallaNotificationsEnabled = useCallback(async (enabled: boolean) => {
    try {
      await IsyncBackgroundLocationModule.setGeovallaNotificationsEnabled(enabled);
      setNotificationsEnabled(enabled);
    } catch (e) {
      throw fail(e);
    }
  }, [fail]);

  const syncNow = useCallback(async () => {
    try {
      await IsyncBackgroundLocationModule.startBatchSync();
      setPendingCount(await IsyncBackgroundLocationModule.getPendingPointsCount());
    } catch (e) {
      throw fail(e);
    }
  }, [fail]);

  const deletePoints = useCallback(
    async (ids: number[]) => {
      try {
        await IsyncBackgroundLocationModule.deletePoints(ids);
        await refreshPendingCount();
      } catch (e) {
        throw fail(e);
      }
    },
    [refreshPendingCount, fail],
  );

  useEffect(() => {
    providerGate.enter();

    const subs: Subscription[] = [];
    try {
      subs.push(
        IsyncBackgroundLocationModule.addListener('location', (point: LocationEventPayload) => {
          setLastLocation(point);
          onLocationRef.current?.(point);
        }),
      );
      subs.push(
        IsyncBackgroundLocationModule.addListener('geovalla', (event: GeoVallaEvent) => {
          setGeovallaTransitions((prev) => [event, ...prev].slice(0, MAX_GEOVALLA_TRANSITIONS));
          onGeoVallaRef.current?.(event);
        }),
      );
    } catch (e) {
      fail(e);
    }
    return () => {
      providerGate.exit();
      subs.forEach((sub) => sub.remove());
    };
  }, [fail]);

  useEffect(() => {
    onStatusChangeRef.current?.(status);
  }, [status]);

  useEffect(() => {
    if (!status.isRunning || pollIntervalMs <= 0) return;
    const id = setInterval(() => {
      void refreshPendingCount();
      void refreshMotionState();
    }, pollIntervalMs);
    return () => clearInterval(id);
  }, [status.isRunning, pollIntervalMs, refreshPendingCount, refreshMotionState]);

  const hasInitialized = useRef(false);
  useEffect(() => {
    if (!autoStart || hasInitialized.current) return;
    hasInitialized.current = true;
    (async () => {
      try {
        const perms = await requestPermissions();
        if (perms.granted) {
          await IsyncBackgroundLocationModule.start(optionsRef.current ?? {});
        }
        await refreshState();
      } catch {
        // El error ya quedó registrado por requestPermissions/start.
      }
    })();
  }, [autoStart, requestPermissions, refreshState]);

  const value = useMemo<IsyncLocationContextValue>(
    () => ({
      available: status.available,
      isRunning: status.isRunning,
      lastLocation,
      geoVallas,
      geovallaTransitions,
      motionState,
      pendingCount,
      notificationsEnabled,
      requestingPermissions,
      error,
      requestPermissions,
      start,
      stop,
      refreshGeoVallas,
      refreshPendingCount,
      setGeovallaNotificationsEnabled,
      syncNow,
      deletePoints,
    }),
    [
      status,
      lastLocation,
      geoVallas,
      geovallaTransitions,
      motionState,
      pendingCount,
      notificationsEnabled,
      requestingPermissions,
      error,
      requestPermissions,
      start,
      stop,
      refreshGeoVallas,
      refreshPendingCount,
      setGeovallaNotificationsEnabled,
      syncNow,
      deletePoints,
    ],
  );

  return (
    <IsyncLocationContext.Provider value={value}>{children}</IsyncLocationContext.Provider>
  );
}

export function useIsyncLocation(): IsyncLocationContextValue {
  const ctx = useContext(IsyncLocationContext);
  if (ctx === null) {
    throw new Error('useIsyncLocation debe usarse dentro de <IsyncLocationProvider>');
  }
  return ctx;
}

export function useLastLocation(): LocationEventPayload | null {
  return useIsyncLocation().lastLocation;
}

export function useGeoVallas(): GeoValla[] {
  return useIsyncLocation().geoVallas;
}

export function useGeoVallaTransitions(): GeoVallaEvent[] {
  return useIsyncLocation().geovallaTransitions;
}

export function usePendingPointCount(): number {
  return useIsyncLocation().pendingCount;
}

export function useIsyncLocationStatus(): { available: boolean; isRunning: boolean } {
  const { available, isRunning } = useIsyncLocation();
  return { available, isRunning };
}