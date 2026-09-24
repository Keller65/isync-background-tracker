# iSync Background Location Tracker

> **Android only.** Módulo nativo de Expo React Native para rastreo de ubicación en
> segundo plano, optimizado para batería. **No soporta iOS ni web.**

## Documentación

- **`docs/DEVELOPER-GUIDE.md`** — guía práctica para devs Expo/React Native:
  instalación, integración, permisos, eventos, geovallas, cola local, checklist de
  prueba con dev build y FAQ.
- **`docs/ENGINEERING.md`** — notas internas de diseño e historial de refactorización.

`isync-background-tracker` combina almacenamiento local en SQLite (con WAL),
transmisión por Protocol Buffers y un motor de geovallas en segundo plano. Todo el
pipeline corre en nativo (un `ForegroundService`), sin depender del puente JS: la app
puede estar cerrada (o el proceso muerto) y el tracking sigue funcionando.

---

## Características

- **Tracking en segundo plano de alto rendimiento** — `ForegroundService` Android con
  `foregroundServiceType="location"`, arranque automático tras `BOOT_COMPLETED`.
- **Ahorro extremo de batería** — sensor de hardware `TYPE_SIGNIFICANT_MOTION`
  (Sensor Hub) + "GPS Espía" para confirmar viajes; el CPU duerme entre fixes.
- **Persistencia durable** — SQLite `isync_tracking.db` con `wal`, tablas
  `location_points` y `geovallas`.
- **Doble canal de red**:
  - *Realtime* — socket.io (websocket) vía protobuf binario.
  - *Batch durable* — `POST /gps/batch` con WorkManager (reintentos + sobrevive al
    kill del proceso y al reboot).
- **Geovallas poligonales** — descarga del servidor, caché local, detección
  entrada/salida y notificación heads-up al entrar.
- **`requestPermissions()` en dos pasos** — FINE + COARSE + notificaciones +
  Activity Recognition, y ascenso a background location (Android 11+).

---

## Instalación

```bash
npm install isync-background-tracker
# o de forma local, para desarrollo:
npm install D:\isync-background-tracker
```

Requiere `expo-modules-core` (`~1.12.0` como dependency) y apps con autolinking de
Expo Modules (Expo SDK 50+, React Native con el plugin de autolinking de Gradle).

> Es un módulo nativo puro: para probarlo necesitas un **dev build** (`npx expo run:android`),
> no sirve Expo Go.

## Configuración de la app

No hace falta configurar nada en el manifiesto: las `uses-permission`, el `<service>`
y los `<receiver>` viajan en el `AndroidManifest.xml` del propio módulo y se
fusionan al build de la app.

## Permisos

```ts
const result = await BackgroundLocation.requestPermissions();
```

Pide, en orden:

1. `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS` y
   `ACTIVITY_RECOGNITION` (Android 10+).
2. Si el usuario concedió los anteriores en Android 10+, se pide
   `ACCESS_BACKGROUND_LOCATION` en un segundo diálogo (obligatorio para el tracking
   con la app en segundo plano).

Devuelve un `PermissionResponse` de `expo-modules-core`. Para tracking en background
el usuario debe aceptar también **"Permitir todo el tiempo"** en los ajustes del
sistema.

---

## Uso rápido

> ### Requisito: el Provider es obligatorio
> **Ninguna** función del módulo (`BackgroundLocation.*`, hooks o listeners)
> funciona si la app no está envuelta en `<IsyncLocationProvider>`. Si un archivo
> intenta llamar al módulo sin el Provider montado, lanza un `Error` indicando que
> envuelvas la app. Envuelve la raíz de tu app (ver "Uso con Provider" más abajo):

```tsx
export default function App() {
  return (
    <IsyncLocationProvider>
      <Root />
    </IsyncLocationProvider>
  );
}
```

Dentro de ese árbol, la API imperativa queda disponible:

```tsx
import { BackgroundLocation } from 'isync-background-tracker';

// 1. Permisos
await BackgroundLocation.requestPermissions();

// 2. Listeners (UI en vivo; la persistencia es nativa y no depende de esto)
const subLoc = BackgroundLocation.addLocationListener((point) =>
  console.log('fix:', point),
);
const subGeo = BackgroundLocation.addGeoVallasListener((event) =>
  console.log('geovalla:', event),
);

// 3. Arrancar el servicio
await BackgroundLocation.start({
  interval: 5000,          // ms entre fixes (default 5000)
  distanceInterval: 1,     // desplazamiento mínimo en m (default 1)
  deviceCode: 'AGRS-01',   // código de vendedor/vehículo
  deviceName: 'Camioneta 05',
  movementConfirmMs: 15000, // confirmación de movimiento antes de reactivar GPS
});

// 4. Con el GPS del dispositivo en alta precisión, camina: cada ~5 s llega un fix.

// 5. Detener
await BackgroundLocation.stop();
```

---

## Uso con Provider (React Context)

Para que "todo funcione solo", envuelve tu app con `IsyncLocationProvider`:
pide permisos, arranca el tracking y suscribe los eventos por ti al montarse.

```tsx
import {
  IsyncLocationProvider,
  useIsyncLocation,
  useLastLocation,
  useGeoVallas,
  usePendingPointCount,
} from 'isync-background-tracker';

function TrackingScreen() {
  const { isRunning, start, stop, geovallaTransitions, error } = useIsyncLocation();
  const last = useLastLocation();          // último fix aceptado
  const geovallas = useGeoVallas();        // cache local
  const pending = usePendingPointCount();  // cola sin subir

  return (
    <>
      {error && <Text>Error: {error.message}</Text>}
      <Text>{isRunning ? 'ACTIVO' : 'detenido'} · pendientes: {pending}</Text>
      <Button title={isRunning ? 'Detener' : 'Iniciar'} onPress={() => (isRunning ? stop() : start())} />
    </>
  );
}

export default function App() {
  return (
    <IsyncLocationProvider options={{ interval: 5000, deviceCode: 'TU-CODIGO' }}>
      <TrackingScreen />
    </IsyncLocationProvider>
  );
}
```

Opciones del Provider:

| Prop | Default | Descripción |
| --- | --- | --- |
| `children` | — | Nodos a renderizar dentro del contexto. |
| `options` | `{}` | Opciones aplicadas a `start()` (autoStart). |
| `autoStart` | `true` | Pide permisos y arranca solo al montar. `false` para gatear detrás de un onboarding (`start()`/`requestPermissions()` manual desde el hook). |
| `pollIntervalMs` | `10000` | Refresco de contadores (`pendingCount`, `motionState`). `0` lo desactiva. |
| `onLocation` | — | Callback por fix (equivalente a `addLocationListener`). |
| `onGeoValla` | — | Callback por transición (equivalente a `addGeoVallasListener`). |
| `onStatusChange` | — | Callback cuando cambia `isRunning`. |
| `onError` | — | Callback ante cualquier error del módulo. |

Hooks disponibles:

- `useIsyncLocation()` — todo el contexto: estado + acciones (`start`, `stop`,
  `refreshGeoVallas`, `syncNow`, `setGeovallaNotificationsEnabled`, `deletePoints`…).
- `useIsyncLocationStatus()` — `{ available, isRunning }`.
- `useLastLocation()` — `LocationEventPayload | null`.
- `useGeoVallas()` — `GeoValla[]`.
- `useGeoVallaTransitions()` — `GeoVallaEvent[]` (recientes, cap 50).
- `usePendingPointCount()` — `number`.

> Nota: el Provider **no detiene** el tracking al desmontarse — es un servicio de
> segundo plano: `stop()` es siempre explícito.

---

## API

> El paquete exporta un objeto único `BackgroundLocation`. Todas sus funciones
> **exigen** que la app esté envuelta en `<IsyncLocationProvider>` (activo mientras
> está montado); sin él lanzan un `Error`. Los hooks requieren el Provider por ser
> Context.

### `requestPermissions(): Promise<PermissionResponse>`
Pide permisos de ubicación (FINE + COARSE + Background), notificaciones y Activity
Recognition. Ver [Permisos](#permisos).

### `start(options?: LocationTrackingOptions): Promise<null>`
Arranca el `ForegroundService`. No lanza error si ya está corriendo (reinicia la
configuración con las opciones dadas). Opciones:

| Campo | Tipo | Default | Descripción |
| --- | --- | --- | --- |
| `interval` | `number` | `5000` | Intervalo objetivo entre fixes, en ms. |
| `distanceInterval` | `number` | `1` | Desplazamiento mínimo entre fixes, en metros (piso espacial: 2 m). |
| `deviceCode` | `string` | `''` | Código del vendedor/vehículo (el deviceId real se toma del Android ID). |
| `deviceName` | `string` | `''` | Nombre descriptivo del dispositivo. |
| `movementConfirmMs` | `number` | `15000` | Tiempo de confirmación del wake por sensores antes de reactivar el GPS (solo Android; más bajo = despierta más agresivo). |

### `stop(): Promise<null>`
Detiene el rastreo, cierra el relay de socket.io, cancela el sensor y guarda el
estado estacionario.

### `getStatus(): Promise<BackgroundLocationStatus>`
```ts
{ available: boolean; isRunning: boolean }
```
`available` es siempre `true` en Android. `isRunning` indica si la captura está activa.

### `getCurrentLocation(): Promise<LocationEventPayload>`
Un fix puntual de alta precisión bajo demanda, sin arrancar el tracking.

### `getMotionState(): Promise<MotionSensorState>`
Estado del detector de movimiento (Significant Motion):
```ts
{ registered: boolean; moving: boolean; accelMagnitude: number; gyroMagnitude: number; magDelta: number }
```
`accelMagnitude`/`gyroMagnitude`/`magDelta` son siempre `0`: el diseño usa solo el
sensor de movimiento significativo por hardware (no hay acelerómetro/giroscopio/
magnetómetro continuos — ver `docs/ENGINEERING.md`).

### `addLocationListener(listener): Subscription`
Evento `location` para cada fix aceptado por el pipeline (best-effort para la UI; la
persistencia es nativa). Devuelve un `Subscription` de `expo-modules-core` (llamar
`.remove()` para desuscribirse).

### `addGeoVallasListener(listener): Subscription`
Evento `geovalla` al **cruzar** un borde de geovalla (`inside: true` al entrar,
`false` al salir). Solo se emite en transiciones, no es spam.

### `getGeoVallas(): Promise<GeoValla[]>`
Lee la caché local de la tabla `geovallas`. Los `rings` vienen como
`GeoPoint[][]` (`{latitude, longitude}`) listos para dibujar; `rings[0]` es el
exterior.

### `refreshGeoVallas(): Promise<GeoValla[]>`
Descarga de nuevo el FeatureCollection de `/api/geovallas` y reemplaza caché en
memoria + tabla SQLite. Resuelve con la lista recién descargada.

### `setGeovallaNotificationsEnabled(enabled: boolean): Promise<null>` / `getGeovallaNotificationsEnabled(): Promise<boolean>`
Activa/desactiva la notificación heads-up al **entrar** en una geovalla
(persistido como `geovalla_notifications`, default `true`). El evento `geovalla` a
JS nunca se silencia.

### `getPendingPoints(limit = 100): Promise<StoredPoint[]>` / `getPendingPointsCount(): Promise<number>` / `deletePoints(ids: number[]): Promise<null>`
Gestión directa de la cola local (`location_points`). Los puntos vienen ordenados
por `timestamp` ASC, con `sincronizado` (0/1) e `intentos`.

### `startBatchSync(): Promise<null>` / `stopBatchSync(): Promise<null>`
Reanuda/cancela el drenado durable de pendientes → `POST /gps/batch` vía WorkManager.
Idempotente (trabajo único por tag). `stopBatchSync` cancela tanto el one-shot como
el periódico.

---

## Tipos

```ts
export type LocationEventPayload = {
  latitude: number; longitude: number; accuracy: number;
  altitude: number; speed: number; heading: number; timestamp: number;
};

export type GeoPoint = { latitude: number; longitude: number };

export type GeoValla = {
  id: number; nombre: string; descripcion: string | null; tipo: string | null;
  activo: boolean; rings: GeoPoint[][]; geojson: string | null; updated: number;
};

export type GeoVallaEvent = {
  id: number; nombre: string; tipo: string | null; activo: boolean;
  inside: boolean; latitude: number; longitude: number; timestamp: number;
};

export type BackgroundLocationStatus = { available: boolean; isRunning: boolean };

export type LocationTrackingOptions = {
  interval?: number; distanceInterval?: number; deviceCode?: string;
  deviceName?: string; movementConfirmMs?: number;
};

export type MotionSensorState = {
  registered: boolean; moving: boolean; accelMagnitude: number;
  gyroMagnitude: number; magDelta: number;
};

export type StoredPoint = {
  id: number; clientUuid: string; latitude: number; longitude: number;
  altitude: number | null; speed: number | null; accuracy: number | null;
  heading: number | null; timestamp: number;
  sincronizado?: number; intentos?: number;
};
```

---

## Cómo funciona por dentro

### Pipeline de captura
`FusedLocationProvider` (alta precisión) → filtros (precisión ≤ 20 m, velocidad
máxima 55 m/s, gate de movimiento 0.5 m/s, distancia elástica) → filtro Kalman →
persistencia en SQLite + relay realtime + evaluación de geovallas + evento a JS.

### Filtros y optimización de batería
- **Significant Motion (hardware)** — `TYPE_SIGNIFICANT_MOTION` en el Sensor Hub
  (microamperios). El CPU duerme al 100% hasta que el hardware detecta movimiento.
- **GPS Espía** — al disparar el sensor se enciende el GPS en modo tentativo; el GPS
  es el árbitro: cruzó ~25 m del ancla de parqueo = viaje real; sin cruzar en 30 s o
  velocidad < 0.5 m/s = falso positivo → borra el registro, apaga GPS y rearma sensor.
- **Fallback** — si el hardware no soporta Significant Motion, se usa
  ActivityRecognition como disparador (solo con el proceso vivo).
- **`movementConfirmMs`** separa movimiento sostenido de un impulso (p. ej. levantar
  el teléfono de la mesa).

### Almacenamiento
SQLite `isync_tracking.db` con `PRAGMA journal_mode=WAL`. Tablas:
- `location_points` — cola pendiente con `clientUuid` (idempotencia), `sincronizado`
  e `intentos`. Mantenimiento: purge a 100 K registros / 7 días.
- `geovallas` — caché de geovallas (autoritativa, `id DESC`, máx. 500).

### Red
- **Realtime** — socket.io (websocket) hacia `https://alfayomega.isynchn.com`, evento
  `join` + `location` (protobuf binario). Reconexión propia del cliente (sin
  `NetworkCallback` — ver `docs/ENGINEERING.md`).
- **Batch** — `POST https://isync-tracker-ws.vercel.app/gps/batch` con
  `Content-Type: application/x-protobuf` y headers `X-Device-Id`/`X-Device-Code`/
  `X-Device-Name`. Lotes de 50, orden `timestamp ASC`. WorkManager: one-shot con
  backoff exponencial (30 s, máx. 5 intentos) + periódico cada 10 min. Solo se
  reintentan `429/5xx/red`; `400/401/422` son fatales (no martillan). Un `200` cuenta
  como aceptado y borra el punto de la cola.

### Geovallas
- Fuente: `GET https://isync-tracker-ws.vercel.app/api/geovallas` (FeatureCollection
  RFC 7946; `[lng, lat]` → convertido a `{latitude, longitude}`).
- Se baja **una vez** al arrancar el Service (y con `refreshGeoVallas()`); no hay
  refresco periódico. El módulo **solo consume**; la escritura la hace la web.
- Cada fix aceptado se evalúa contra las geovallas activas por point-in-polygon (ray
  casting); al cruzar un borde se emite `geovalla`. El primer fix reporta solo `enter`.
- Notificación heads-up al entrar (canal `geovalla_events`, sonido
  `res/raw/location.mp3`), controlable desde JS.

---

## Pruebas

Dos capas de tests, para que un cambio de la superficie TS no rompa el contrato
y un cambio de geovallas no rompa el parsing nativo:

```bash
# 1) JS/TS (jest + ts-jest): contrato de exports, Provider, hooks y
#    delegación al módulo nativo (mockeado). Corre en CI sin SDK.
npm test

# 2) Kotlin/JUnit (lógica nativa pura: geovallas, anillos, FeatureCollection).
#    Se compilan y corren desde la app que consume el módulo:
cd <tu-app-expo> && ./gradlew :isync-background-tracker:testDebugUnitTest
```

## Pruebas (dev build)

Como es un módulo de código nativo, se compila e instala con un dev build:

```bash
# En la app que consume el módulo
npx expo run:android
```

Requisitos: JDK 17+, `ANDROID_HOME` definido, y un emulador **con Google APIs** o un
dispositivo físico. Para probar el tracking es necesario GPS en alta precisión.

Ver los logs del módulo:

```bash
adb logcat -s IsyncBgLocation -v time
```

---

## Limitaciones

- **Solo Android** (no iOS, no web).
- La geofence de reposo con proceso muerto (`GeofencingClient`) fue removida: con el
  proceso cerrado y el dispositivo quieto, solo el sensor de movimiento significativo
  (IMM) puede reactivar el tracking — ver `docs/ENGINEERING.md`.

## Licencia

MIT © 2026 iSync