import { registerWebModule, NativeModule } from 'expo';
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

const UNSUPPORTED = 'isync-background-location es un módulo solo para Android';

class IsyncBackgroundLocationModule extends NativeModule<IsyncBackgroundLocationModuleEvents> {
  async getStatus(): Promise<BackgroundLocationStatus> {
    return { available: false, isRunning: false };
  }

  async getCurrentLocation(): Promise<LocationEventPayload> {
    throw new Error(UNSUPPORTED);
  }

  async start(_options: LocationTrackingOptions): Promise<null> {
    throw new Error(UNSUPPORTED);
  }

  async stop(): Promise<null> {
    throw new Error(UNSUPPORTED);
  }

  async requestPermissions(): Promise<PermissionResponse> {
    throw new Error(UNSUPPORTED);
  }

  async getPendingPoints(_limit: number): Promise<StoredPoint[]> {
    return [];
  }

  async getPendingPointsCount(): Promise<number> {
    return 0;
  }

  async deletePoints(_ids: number[]): Promise<null> {
    throw new Error(UNSUPPORTED);
  }

  async startBatchSync(): Promise<null> {
    throw new Error(UNSUPPORTED);
  }

  async stopBatchSync(): Promise<null> {
    throw new Error(UNSUPPORTED);
  }

  async getMotionState(): Promise<MotionSensorState> {
    return { registered: false, moving: true, accelMagnitude: 0, gyroMagnitude: 0, magDelta: 0 };
  }

  async getGeoVallas(): Promise<GeoValla[]> {
    return [];
  }

  async refreshGeoVallas(): Promise<GeoValla[]> {
    throw new Error(UNSUPPORTED);
  }

  async setGeovallaNotificationsEnabled(_enabled: boolean): Promise<null> {
    return null;
  }

  async getGeovallaNotificationsEnabled(): Promise<boolean> {
    return true;
  }
}

export default registerWebModule(IsyncBackgroundLocationModule, 'IsyncBackgroundLocationModule');
