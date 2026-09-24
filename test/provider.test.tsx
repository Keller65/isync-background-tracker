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
  getCurrentLocation: jest.fn(async () => null),
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

import * as React from 'react';
import { act, create, ReactTestRenderer } from 'react-test-renderer';

import {
  IsyncLocationProvider,
  useIsyncLocation,
  useIsyncLocationStatus,
  useGeoVallas,
  useGeoVallaTransitions,
  useLastLocation,
  usePendingPointCount,
} from '../src/IsyncLocationProvider';

import type {
  GeoVallaEvent,
  LocationEventPayload,
} from '../src/IsyncBackgroundLocation.types';
import { providerGate } from '../src/ProviderGate';

function emit(event: string, payload: any) {
  mockListeners[event]?.(payload);
}

const samplePoint: LocationEventPayload = {
  latitude: 19.4326,
  longitude: -99.1332,
  accuracy: 8,
  altitude: 2240,
  speed: 1.2,
  heading: 90,
  timestamp: 1700000000000,
};

const sampleEvent: GeoVallaEvent = {
  id: 7,
  nombre: 'Zona A',
  tipo: 'poligono',
  activo: true,
  inside: true,
  latitude: 19.4326,
  longitude: -99.1332,
  timestamp: 1700000000001,
};

let renderer: ReactTestRenderer | undefined;
let ctx: any = null;

function Probe() {
  ctx = useIsyncLocation();
  return null;
}

async function renderProvider(
  children: React.ReactElement,
  props: React.ComponentProps<typeof IsyncLocationProvider> = {},
) {
  let r: ReactTestRenderer | undefined;
  await act(async () => {
    r = create(<IsyncLocationProvider {...props}>{children}</IsyncLocationProvider>);
  });
  renderer = r;
}

beforeEach(() => {
  jest.clearAllMocks();
  Object.keys(mockListeners).forEach((k) => delete mockListeners[k]);
  ctx = null;
  mockModule.getStatus.mockResolvedValue({ available: true, isRunning: false });
});

afterEach(() => {
  act(() => {
    renderer?.unmount();
    renderer = undefined;
  });
});

describe('IsyncLocationProvider', () => {
  it('activa el providerGate al montar y lo libera al desmontar', async () => {
    expect(providerGate.isActive()).toBe(false);

    await renderProvider(<Probe />, { autoStart: false });
    expect(providerGate.isActive()).toBe(true);

    act(() => {
      renderer?.unmount();
      renderer = undefined;
    });
    expect(providerGate.isActive()).toBe(false);
  });

  it('con autoStart=true pide permisos, arranca y refresca el estado al montar', async () => {
    await renderProvider(<Probe />, { options: { interval: 5000 } });

    expect(mockModule.requestPermissions).toHaveBeenCalledTimes(1);
    expect(mockModule.start).toHaveBeenCalledWith({ interval: 5000 });
    expect(mockModule.getStatus).toHaveBeenCalled();
    expect(mockModule.getGeoVallas).toHaveBeenCalled();
    expect(mockModule.getGeovallaNotificationsEnabled).toHaveBeenCalled();
    expect(mockModule.getMotionState).toHaveBeenCalled();
    expect(mockModule.getPendingPointsCount).toHaveBeenCalled();

    expect(ctx).not.toBeNull();
    expect(ctx.available).toBe(true);
    expect(ctx.isRunning).toBe(false);
    expect(ctx.lastLocation).toBeNull();
    expect(ctx.pendingCount).toBe(0);
    expect(ctx.notificationsEnabled).toBe(true);
    expect(ctx.error).toBeNull();
  });

  it('con autoStart=false no pide permisos ni arranca al montar', async () => {
    await renderProvider(<Probe />, { autoStart: false });

    expect(mockModule.requestPermissions).not.toHaveBeenCalled();
    expect(mockModule.start).not.toHaveBeenCalled();
    expect(mockModule.getStatus).not.toHaveBeenCalled();
  });

  it('delega las acciones start/stop/refresh/sync manuales al módulo', async () => {
    await renderProvider(<Probe />, { autoStart: false });

    await act(async () => {
      await ctx.start({ interval: 1000, deviceCode: 'B2' });
    });
    expect(mockModule.start).toHaveBeenCalledWith({ interval: 1000, deviceCode: 'B2' });
    const statusCallsAfterStart = mockModule.getStatus.mock.calls.length;

    await act(async () => {
      await ctx.stop();
    });
    expect(mockModule.stop).toHaveBeenCalledTimes(1);
    expect(mockModule.getStatus).toHaveBeenCalledTimes(statusCallsAfterStart + 1);

    await act(async () => {
      await ctx.refreshGeoVallas();
    });
    expect(mockModule.refreshGeoVallas).toHaveBeenCalledTimes(1);

    await act(async () => {
      await ctx.setGeovallaNotificationsEnabled(false);
    });
    expect(mockModule.setGeovallaNotificationsEnabled).toHaveBeenCalledWith(false);
    expect(ctx.notificationsEnabled).toBe(false);

    await act(async () => {
      await ctx.syncNow();
    });
    expect(mockModule.startBatchSync).toHaveBeenCalledTimes(1);
    expect(mockModule.getPendingPointsCount).toHaveBeenCalled();

    await act(async () => {
      await ctx.deletePoints([1, 2]);
    });
    expect(mockModule.deletePoints).toHaveBeenCalledWith([1, 2]);
    expect(mockModule.getPendingPointsCount).toHaveBeenCalled();
  });

  it('registra los listeners nativos location/geovalla y deriva el estado', async () => {
    const onLocation = jest.fn();
    const onGeoValla = jest.fn();
    let last: any = null;
    let transitions: any = null;
    const ProbeSlices = () => {
      last = useLastLocation();
      transitions = useGeoVallaTransitions();
      return null;
    };

    await renderProvider(<ProbeSlices />, { autoStart: false, onLocation, onGeoValla });

    expect(mockModule.addListener).toHaveBeenCalledWith('location', expect.any(Function));
    expect(mockModule.addListener).toHaveBeenCalledWith('geovalla', expect.any(Function));

    await act(async () => {
      emit('location', samplePoint);
    });
    expect(last).toEqual(samplePoint);
    expect(onLocation).toHaveBeenCalledWith(samplePoint);

    await act(async () => {
      emit('geovalla', sampleEvent);
    });
    expect(transitions).toHaveLength(1);
    expect(transitions[0]).toEqual(sampleEvent);
    expect(onGeoValla).toHaveBeenCalledWith(sampleEvent);

    for (let i = 0; i < 12; i++) {
      await act(async () => {
        emit('geovalla', { ...sampleEvent, id: 100 + i });
      });
    }
    expect(transitions).toHaveLength(13);
  });

  it('mantiene el cap de transiciones en MAX_GEOVALLA_TRANSITIONS', async () => {
    let transitions: any = null;
    const ProbeSlices = () => {
      transitions = useGeoVallaTransitions();
      return null;
    };

    await renderProvider(<ProbeSlices />, { autoStart: false });

    for (let i = 0; i < 60; i++) {
      await act(async () => {
        emit('geovalla', { ...sampleEvent, id: i });
      });
    }
    expect(transitions).toHaveLength(50);
  });

  it('registra fallos de acciones en error del contexto y onError', async () => {
    const onError = jest.fn();
    const ErrorProbe = () => {
      ctx = useIsyncLocation();
      return null;
    };

    await renderProvider(<ErrorProbe />, { autoStart: false, onError });

    mockModule.start.mockRejectedValueOnce(new Error('boom'));
    let threw: unknown = null;
    await act(async () => {
      try {
        await ctx.start();
      } catch (e) {
        threw = e;
      }
    });

    expect(threw).toBeInstanceOf(Error);
    expect((threw as Error).message).toBe('boom');
    expect(ctx.error?.message).toBe('boom');
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(expect.any(Error));
  });

  it('los hooks selector lanzan un error claro fuera del Provider', () => {
    let msg = '';
    const Bad = () => {
      useIsyncLocation();
      return null;
    };
    try {
      create(<Bad />);
    } catch (e: any) {
      msg = e['message'] as string;
    }
    expect(msg).toMatch(/useIsyncLocation/);
    expect(msg).toMatch(/IsyncLocationProvider/);
  });

  it('el estado de running llega a onStatusChange', async () => {
    const onStatusChange = jest.fn();
    mockModule.getStatus.mockResolvedValue({ available: true, isRunning: true });

    await renderProvider(<Probe />, {
      onStatusChange,
    });

    expect(onStatusChange).toHaveBeenCalled();
    expect(onStatusChange).toHaveBeenLastCalledWith({ available: true, isRunning: true });
  });

  it('los hooks selector exponen slices del estado', async () => {
    mockModule.getGeoVallas.mockResolvedValue([
      {
        id: 1,
        nombre: 'A',
        descripcion: null,
        tipo: 'poligono',
        activo: true,
        rings: [],
        geojson: null,
        updated: 0,
      },
    ]);
    mockModule.getPendingPointsCount.mockResolvedValue(4);

    let status: any = null;
    let gv: any = null;
    let pc: any = null;
    const ProbeSlices = () => {
      ctx = useIsyncLocation();
      status = useIsyncLocationStatus();
      gv = useGeoVallas();
      pc = usePendingPointCount();
      return null;
    };

    await renderProvider(<ProbeSlices />, { autoStart: false });

    await act(async () => {
      await ctx.start();
    });

    expect(status).toEqual({ available: true, isRunning: false });
    expect(gv).toHaveLength(1);
    expect(gv[0].nombre).toBe('A');
    expect(pc).toBe(4);
  });
});