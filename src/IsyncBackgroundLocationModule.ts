import { requireNativeModule, NativeModule } from 'expo';
import type { PermissionResponse } from 'expo-modules-core';

import type {
  BackgroundLocationStatus,
  GeoValla,
  IsyncBackgroundLocationModuleEvents,
  LocationEventPayload,
  LocationTrackingOptions,
  MotionSensorState,
  StoredPoint,
} from './IsyncBackgroundLocation.types';

declare class IsyncBackgroundLocationModule extends NativeModule<IsyncBackgroundLocationModuleEvents> {
  getStatus(): Promise<BackgroundLocationStatus>;
  getCurrentLocation(): Promise<LocationEventPayload>;
  start(options: LocationTrackingOptions): Promise<null>;
  stop(): Promise<null>;
  requestPermissions(): Promise<PermissionResponse>;
  getPendingPoints(limit: number): Promise<StoredPoint[]>;
  getPendingPointsCount(): Promise<number>;
  deletePoints(ids: number[]): Promise<null>;
  startBatchSync(): Promise<null>;
  stopBatchSync(): Promise<null>;
  /** Solo Android. Ver `BackgroundLocation.getMotionState()`. */
  getMotionState(): Promise<MotionSensorState>;
  /** Cache local de geovallas (tabla SQLite nativa). Ver `BackgroundLocation.getGeoVallas()`. */
  getGeoVallas(): Promise<GeoValla[]>;
  /** GET al servidor y reemplaza cache + tabla SQLite. Ver `BackgroundLocation.refreshGeoVallas()`. */
  refreshGeoVallas(): Promise<GeoValla[]>;
  /** Flag nativo de notificaciones de geovallas. Ver `BackgroundLocation.setGeovallaNotificationsEnabled()`. */
  setGeovallaNotificationsEnabled(enabled: boolean): Promise<null>;
  getGeovallaNotificationsEnabled(): Promise<boolean>;
}

export default requireNativeModule<IsyncBackgroundLocationModule>('IsyncBackgroundLocation');
