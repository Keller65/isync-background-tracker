const mockListeners: Record<string, (payload: any) => void> = {};
const mockModule: any = {
  requestPermissions: jest.fn(async () => ({
    canAskAgain: true,
    expires: 'never',
    granted: true,
    status: 'granted',
  })),
  start: jest.fn(async () => null),
  stop: jest.fn(async () => null),
  getStatus: jest.fn(async () => ({ available: true, isRunning: false })),
  getCurrentLocation: jest.fn(async () => ({
    latitude: 19.4,
    longitude: -99.1,
    accuracy: 8,
    altitude: 2200,
    speed: 0,
    heading: 0,
    timestamp: 1700000000000,
  })),
  getMotionState: jest.fn(async () => ({
    registered: true,
    moving: false,
    accelMagnitude: 0,
    gyroMagnitude: 0,
    magDelta: 0,
  })),
  getGeoVallas: jest.fn(async () => []),
  refreshGeoVallas: jest.fn(async () => []),
  getGeovallaNotificationsEnabled: jest.fn(async () => true),
  setGeovallaNotificationsEnabled: jest.fn(async () => null),
  getPendingPoints: jest.fn(async () => []),
  getPendingPointsCount: jest.fn(async () => 0),
  deletePoints: jest.fn(async () => null),
  startBatchSync: jest.fn(async () => null),
  stopBatchSync: jest.fn(async () => null),
  addListener: jest.fn((event: string, cb: (payload: any) => void) => {
    mockListeners[event] = cb;
    return { remove: jest.fn() };
  }),
};

jest.mock('../src/IsyncBackgroundLocationModule', () => ({
  __esModule: true,
  default: mockModule,
}));

import {
  BackgroundLocation,
  IsyncLocationProvider,
  useIsyncLocation,
  useIsyncLocationStatus,
  useGeoVallas,
  useGeoVallaTransitions,
  useLastLocation,
  usePendingPointCount,
} from '../src';

import IsyncBackgroundLocationModule from '../src/IsyncBackgroundLocationModule';
import { providerGate } from '../src/ProviderGate';

beforeEach(() => {
  jest.clearAllMocks();
  Object.keys(mockListeners).forEach((k) => delete mockListeners[k]);
});

beforeAll(() => {
  providerGate.enter();
});

afterAll(() => {
  providerGate.exit();
});

describe('Gate de Provider', () => {
  it('lanza un error claro si se llama sin <IsyncLocationProvider>', () => {
    providerGate.exit(); // desactiva el gate del beforeAll para simular app sin provider

    expect(() => BackgroundLocation.getStatus()).toThrow(/IsyncLocationProvider/);
    expect(() => BackgroundLocation.start()).toThrow(/IsyncLocationProvider/);
    expect(() => BackgroundLocation.addLocationListener(() => undefined)).toThrow(
      /IsyncLocationProvider/,
    );
    expect(() => BackgroundLocation.getPendingPointsCount()).toThrow(/IsyncLocationProvider/);

    providerGate.enter(); // reactiva para el resto de los tests
  });

  it('no llama al nativo si el gate está apagado', () => {
    providerGate.exit();
    try {
      BackgroundLocation.stop();
    } catch {
      // esperado
    }

    providerGate.enter();
    expect(IsyncBackgroundLocationModule.stop).not.toHaveBeenCalled();
  });
});

describe('Superficie pública (contrato)', () => {
  it('expone todos los métodos del contrato en BackgroundLocation', () => {
    const expected = [
      'requestPermissions',
      'start',
      'stop',
      'getStatus',
      'getCurrentLocation',
      'getMotionState',
      'addLocationListener',
      'addGeoVallasListener',
      'getGeoVallas',
      'refreshGeoVallas',
      'setGeovallaNotificationsEnabled',
      'getGeovallaNotificationsEnabled',
      'getPendingPoints',
      'getPendingPointsCount',
      'deletePoints',
      'startBatchSync',
      'stopBatchSync',
    ];
    for (const method of expected) {
      expect(typeof BackgroundLocation[method as keyof typeof BackgroundLocation]).toBe('function');
    }
    expect(Object.keys(BackgroundLocation).sort()).toEqual([...expected].sort());
  });

  it('expone el Provider y todos los hooks', () => {
    expect(typeof IsyncLocationProvider).toBe('function');
    expect(typeof useIsyncLocation).toBe('function');
    expect(typeof useIsyncLocationStatus).toBe('function');
    expect(typeof useLastLocation).toBe('function');
    expect(typeof useGeoVallas).toBe('function');
    expect(typeof useGeoVallaTransitions).toBe('function');
    expect(typeof usePendingPointCount).toBe('function');
  });

  it('start() delega al módulo nativo con las opciones pasadas', async () => {
    const opts = { interval: 5000, distanceInterval: 1, deviceCode: 'A1' };
    await expect(BackgroundLocation.start(opts)).resolves.toBeNull();
    expect(IsyncBackgroundLocationModule.start).toHaveBeenCalledWith(opts);
  });

  it('start() usa default {} cuando no se pasan opciones', async () => {
    BackgroundLocation.start();
    expect(IsyncBackgroundLocationModule.start).toHaveBeenCalledWith({});
  });

  it('getPendingPoints(limit) delega el límite al nativo', async () => {
    await BackgroundLocation.getPendingPoints(12);
    expect(IsyncBackgroundLocationModule.getPendingPoints).toHaveBeenCalledWith(12);
  });

  it('getPendingPoints() sin argumento delega el default 100', async () => {
    BackgroundLocation.getPendingPoints();
    expect(IsyncBackgroundLocationModule.getPendingPoints).toHaveBeenCalledWith(100);
  });

  it('los listeners del contrato se suscriben al evento nativo correcto', () => {
    const loc = BackgroundLocation.addLocationListener(() => undefined);
    const geo = BackgroundLocation.addGeoVallasListener(() => undefined);

    expect(IsyncBackgroundLocationModule.addListener).toHaveBeenCalledWith(
      'location',
      expect.any(Function),
    );
    expect(IsyncBackgroundLocationModule.addListener).toHaveBeenCalledWith(
      'geovalla',
      expect.any(Function),
    );

    expect(typeof (loc as { remove: () => void }).remove).toBe('function');
    expect(typeof (geo as { remove: () => void }).remove).toBe('function');
  });

  it('el resto de acciones de control delegan al módulo', async () => {
    await BackgroundLocation.getStatus();
    await BackgroundLocation.getMotionState();
    await BackgroundLocation.getCurrentLocation();
    await BackgroundLocation.getGeoVallas();
    await BackgroundLocation.refreshGeoVallas();
    await BackgroundLocation.setGeovallaNotificationsEnabled(false);
    await BackgroundLocation.getGeovallaNotificationsEnabled();
    await BackgroundLocation.deletePoints([1, 2]);
    await BackgroundLocation.startBatchSync();
    await BackgroundLocation.stopBatchSync();

    expect(IsyncBackgroundLocationModule.getStatus).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.getMotionState).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.getCurrentLocation).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.getGeoVallas).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.refreshGeoVallas).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.setGeovallaNotificationsEnabled).toHaveBeenCalledWith(false);
    expect(IsyncBackgroundLocationModule.getGeovallaNotificationsEnabled).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.deletePoints).toHaveBeenCalledWith([1, 2]);
    expect(IsyncBackgroundLocationModule.startBatchSync).toHaveBeenCalledTimes(1);
    expect(IsyncBackgroundLocationModule.stopBatchSync).toHaveBeenCalledTimes(1);
  });
});