# Guía para desarrolladores Expo / React Native

Guía práctica para integrar `isync-background-tracker` en tu app. Es un **módulo
nativo Android**: solo funciona en Android, con un **dev build** (Expo Go no lo
soporta), y requiere `expo-modules-core` (`~1.12.0`).

---

## 1. Prerrequisitos

| Requisito | Detalle |
| --- | --- |
| Plataforma | Android (no iOS, no web) |
| Expo SDK | 50+ (autolinking de Expo Modules) |
| Build | Dev build: `npx expo run:android` |
| Dispositivo | Emulador **con Google APIs** o teléfono físico |
| Permiso del sistema | GPS en **alta precisión** para probar el tracking |

---

## 2. Instalación

```bash
npm install isync-background-tracker
```

Se instala solo: sin configuración de manifiesto (el módulo trae sus propias
`uses-permission`, `<service>` y `<receiver>`), sin plugin de config para
`app.json`/`app.config.js`.

> En desarrollo contra el código fuente:
> ```bash
> npm install D:\isync-background-tracker
> ```
> Después, reconstruye el dev build para regenerar el autolinking.

---

## 3. Flujo mínimo de integración

> ### Regla de oro: sin Provider no hay nada
> Toda la API del módulo está bloqueada tras un **gate de runtime**: si la app no
> está envuelta en `<IsyncLocationProvider>`, cualquier llamada a
> `BackgroundLocation.*` lanza un `Error` al momento (no llama al nativo). El
> Provider sólo está activo mientras esté montado. Lo primero que hace tu app debe
> ser montar el Provider (basta con que envuelva la raíz):

```tsx
export default function App() {
  return <IsyncLocationProvider>{/* tu árbol */}</IsyncLocationProvider>;
}
```

Dentro de ese árbol, el flujo imperativo funciona normalmente:

```tsx
import { useEffect, useState } from 'react';
import { View, Text, Button, StyleSheet } from 'react-native';
import { BackgroundLocation } from 'isync-background-tracker';

export default function HomeScreen() {
  const [status, setStatus] = useState({ available: true, isRunning: false });

  useEffect(() => {
    // 1) Pedir permisos en el primer arranque
    BackgroundLocation.requestPermissions().then(console.log);

    // 2) Listeners de UI en vivo (opcionales; la persistencia es nativa)
    const loc = BackgroundLocation.addLocationListener((p) =>
      console.log('fix', p.latitude, p.longitude, p.accuracy, p.timestamp),
    );
    const geo = BackgroundLocation.addGeoVallasListener((e) =>
      console.log(e.inside ? 'entró' : 'salió', e.nombre),
    );

    // 3) Estado inicial del servicio
    BackgroundLocation.getStatus().then(setStatus);

    return () => {
      loc.remove();
      geo.remove();
    };
  }, []);

  const toggle = async () => {
    if (status.isRunning) {
      await BackgroundLocation.stop();
    } else {
      await BackgroundLocation.start({
        interval: 5000,
        distanceInterval: 1,
        deviceCode: 'TU_CODIGO', // opcional: código de vendedor/vehículo
        deviceName: 'Equipo 01', // opcional
      });
    }
    setStatus(await BackgroundLocation.getStatus());
  };

  return (
    <View style={styles.box}>
      <Text>Estado: {status.isRunning ? 'ACTIVO' : 'detenido'}</Text>
      <Button title={status.isRunning ? 'Detener' : 'Iniciar'} onPress={toggle} />
    </View>
  );
}

const styles = StyleSheet.create({ box: { padding: 32 } });
```

### Orden recomendado

1. `requestPermissions()` — una vez, típicamente en onboarding.
2. `start(options)` — al arrancar la pantalla de tracking (o al entrar al map).
3. Escuchar eventos en vivo cuando la pantalla está visible; `.remove()` al salir.
4. `stop()` — al cerrar sesión/pantalla de tracking. El servicio sigue vivo tras
   `BOOT_COMPLETED` si se detuvo solo por el usuario.

---

## 4. Permisos de ubicación (detalle)

`requestPermissions()` hace el flujo de dos pasos de Android:

1. Pide `FINE + COARSE + POST_NOTIFICATIONS (+ ACTIVITY_RECOGNITION` en Android 10+).
2. Si concedió el primer bloque, pide **`ACCESS_BACKGROUND_LOCATION`** en un segundo
   diálogo (Android 11+).

Para que el tracking funcione con la app cerrada, el usuario debe elegir
**"Permitir todo el tiempo"** (el "Mientras se usa la app" NO alcanza para
background). La UI debería:

- Mostrar un screen de onboarding explicando **para qué** se usa la ubicación y que
  se necesita "todo el tiempo".
- Si `requestPermissions()` no devuelve todo concedido, ofrecer un botón que abra
  los ajustes del sistema del flujo *fuse*d:

```ts
import * as IntentLauncher from 'expo-intent-launcher';

await IntentLauncher.startActivityAsync(
  IntentLauncher.ActivityAction.LOCATION_SOURCE_SETTINGS,
);
```

El módulo **no** reactiva permisos automáticamente: si el usuario revoca el permiso
de background, `start()` seguirá capturando solo en primer plano (y Android puede
matar el Service).

---

## 3b. Modo "todo en uno": Provider + hooks (recomendado)

Si no quieres administrar permisos/start/listeners a mano, envuelve tu app con
`IsyncLocationProvider`: al montarse pide permisos, arranca el tracking y
suscribe los eventos. Toda la API queda disponible vía hooks. Además, **montar el
Provider es obligatorio para que funcione la API imperativa**: el módulo bloquea
`BackgroundLocation.*` con un error si el Provider no está montado.

```tsx
import * as React from 'react';
import { View, Text, Button } from 'react-native';
import {
  IsyncLocationProvider,
  useIsyncLocation,
  useLastLocation,
  useGeoVallas,
  usePendingPointCount,
} from 'isync-background-tracker';

function TrackingUI() {
  const { isRunning, start, stop, error } = useIsyncLocation();
  const last = useLastLocation();
  const geovallas = useGeoVallas();
  const pending = usePendingPointCount();

  return (
    <View style={{ padding: 32 }}>
      {error && <Text style={{ color: 'red' }}>{error.message}</Text>}
      <Text>Estado: {isRunning ? 'ACTIVO' : 'detenido'}</Text>
      <Text>Último fix: {last ? `${last.latitude}, ${last.longitude}` : '—'}</Text>
      <Text>Geovallas: {geovallas.length} · Pendientes: {pending}</Text>
      <Button title={isRunning ? 'Detener' : 'Iniciar'}
        onPress={() => (isRunning ? stop() : start())} />
    </View>
  );
}

export default function App() {
  return (
    <IsyncLocationProvider options={{ interval: 5000 }}>
      <TrackingUI />
    </IsyncLocationProvider>
  );
}
```

### Props del Provider

| Prop | Default | Descripción |
| --- | --- | --- |
| `options` | `{}` | Opciones de `start()` (autoStart). |
| `autoStart` | `true` | true = pide permisos y arranca al montar. `false` = gatea tras onboarding; llama `start()`/`requestPermissions()` desde el hook. |
| `pollIntervalMs` | `10000` | Refresco de `pendingCount`/`motionState`. `0` lo desactiva. |
| `onLocation` / `onGeoValla` | — | Equivalentes a los listeners (evita suscribirte a mano). |
| `onStatusChange` / `onError` | — | Callbacks de estado y errores. |

### Hooks

| Hook | Retorna |
| --- | --- |
| `useIsyncLocation()` | Contexto completo: `isRunning`, `lastLocation`, `geoVallas`, `geovallaTransitions`, `motionState`, `pendingCount`, `notificationsEnabled`, `error`, y acciones `start`/`stop`/`requestPermissions`/`refreshGeoVallas`/`syncNow`/`setGeovallaNotificationsEnabled`/`deletePoints`. |
| `useIsyncLocationStatus()` | `{ available, isRunning }` |
| `useLastLocation()` | Último fix aceptado o `null`. |
| `useGeoVallas()` | `GeoValla[]` en cache. |
| `useGeoVallaTransitions()` | Eventos de geovalla recientes (cap 50). |
| `usePendingPointCount()` | Puntos pendientes de subir. |

> El Provider **no** detiene el tracking al desmontarse: es un servicio de segundo
> plano, `stop()` es explícito. Los listeners se limpian solos al desmontar.

---

## 5. Eventos en vivo

### `addLocationListener` — cada fix aceptado por el pipeline
```ts
const sub = BackgroundLocation.addLocationListener((point) => {
  // point: LocationEventPayload
});
sub.remove(); // al desmontar la pantalla
```
Frecuencia real: depende del movimiento y del `interval` (default 5 s). Fixes
descartados por filtros (precisión, velocidad, reposo) **no** llegan acá ni se
persisten.

### `addGeoVallasListener` — transiciones de geovallas
```ts
BackgroundLocation.addGeoVallasListener((event) => {
  // event.inside === true  : entró
  // event.inside === false : salió
});
```
Solo se emite cuando se **cruza un borde** (no hay spam de "sigues dentro"). El
primer fix de cada viaje reporta `enter` para que la UI aprenda el estado inicial.

---

## 6. Geovallas

Las geovallas se descargan del backend (`GET /api/geovallas`) **una vez al arrancar
el Service** y se cachean en SQLite. El módulo **solo consume**: edítalas desde la
web (techweb).

```ts
// Leer cache local (sobrevive a kills y a arranque sin red)
const geovallas = await BackgroundLocation.getGeoVallas();
// → GeoValla[]: { id, nombre, rings: GeoPoint[][], activo, ... }
// rings[0] es el exterior, dibujable directo en tu mapa (latitude/longitude).

// Forzar descarga fresca + reemplazo de cache (al agregar/editar en la web)
const fresh = await BackgroundLocation.refreshGeoVallas();
```

Notificaciones heads-up al **entrar** (controlable por el usuario):

```ts
await BackgroundLocation.setGeovallaNotificationsEnabled(true);
const enabled = await BackgroundLocation.getGeovallaNotificationsEnabled();
```

---

## 7. Cola local y sincronización por lotes

Cada fix aceptado se persiste en SQLite (WAL) **antes** de cualquier red. La app que
consume no necesita "subir" puntos manualmente: el relay en tiempo real y el batch
corren en nativo.

Solo usa la API de cola para el panel de debug:

```ts
const count = await BackgroundLocation.getPendingPointsCount();
const points = await BackgroundLocation.getPendingPoints(100); // los más antiguos
await BackgroundLocation.deletePoints([ids]); // depuración manual

// El worker durable se agenda solo al arrancar el Service, pero puedes:
await BackgroundLocation.startBatchSync();  // forzar drenado ahora
await BackgroundLocation.stopBatchSync();   // cancelar
```

El batch hace `POST /gps/batch` (protobuf) con WorkManager: reintento con backoff en
`429/5xx/red`, no martilla en `400/401/422`. Un punto `sincronizado: 1` fue
confirmado por el server.

---

## 8. Estado de movimiento (debug/batería)

```ts
const m = await BackgroundLocation.getMotionState();
// { registered, moving, accelMagnitude, gyroMagnitude, magDelta }
```
- `registered: true` → el sensor de movimiento significativo (hardware) está armado
  y el CPU duerme.
- `moving: true` → el dispositivo no está estacionario (capturando).
- Las magnitudes son siempre `0` (el diseño no usa sensores continuos).

Es útil para un indicador en pantalla ("sensores calibrados / dispositivo quieto").

---

## 9. Tests automatizados del módulo

El paquete trae dos capas; ninguna necesita la app de consumo, salvo la nativa.

### Capa JS/TS (`npm test`, corre en CI sin SDK)

Jest + ts-jest sobre `test/`. Con el módulo nativo mockeado cubre:

- **Superficie del contrato**: `BackgroundLocation` expone exactamente los 17 métodos
  del API; los defaults de `start()`/`getPendingPoints()` y la delegación de cada
  método al nativo.
- **Provider + hooks**: `autoStart` on/off, derivación de estado desde los listeners
  (`location`/`geovalla`), cap de `geovallaTransitions` (50), manejo de errores
  (`onError`/`error` en contexto), hooks selector y error claro fuera del Provider.
- **Gate del Provider**: la API imperativa lanza un `Error` (y no toca el nativo)
  si se llama sin `<IsyncLocationProvider>` montado, y se reactiva al montarlo.

```bash
npm test            # una pasada
npm run test:watch  # watch
```

> `tsconfig.test.json` extiende `tsconfig.json` y solo agrega `"types": ["jest"]`.
> El build de publicación (`npm run build`) no compila `test/`.

### Capa Kotlin/JVM (se corre desde la app que consume el módulo)

`android/src/test/` → `GeoVallasLogicTest.kt` valida la lógica nativa pura:

- `GeoValla.contains` (ray casting): dentro/fuera, agujeros, bordes.
- `ringsJson`/`parseRingsJson`: roundtrip y el mapeo `[lng, lat]` → `{latitude, longitude}`.
- `parseFeatureCollection`: ignora geometrías no-Polygon, rellena `nombre`/`tipo`/
  `activO` con fallbacks del contrato REST.
- `GeoValla.toMap`: los `rings` expuestos al JS en `{latitude, longitude}`.

```bash
# Desde la app Expo que instala el módulo (los tests viven en el proyecto del módulo):
cd <tu-app-expo>
# Windows:  gradlew.bat :isync-background-tracker:testDebugUnitTest
# Linux/mac: ./gradlew :isync-background-tracker:testDebugUnitTest
```

`android/build.gradle` agrega `testImplementation junit + org.json:json` (sombra
de los stubs de `android.jar`) y `unitTests.returnDefaultValues = true`.

## 10. Checklist de prueba en dev build

```bash
# 1) Conecta emulador (Google APIs) o teléfono
adb devices

# 2) Compila e instala (valida todo el Kotlin + protobuf del módulo)
npx expo run:android

# 3) Logs del módulo
adb logcat -s IsyncBgLocation -v time
```

Escenarios a validar:

| Escenario | Esperado en log/UI |
| --- | --- |
| `requestPermissions()` | todos `granted: true`; segundo diálogo de background aparece en Android 11+ |
| `start()` + caminar con GPS alta precisión | `[LOCATION]` cada ~5 s con `accuracy` ≤ 20 m |
| Teléfono quieto 5 min | servicio entra a reposo: GPS apagado, `registered: true` |
| Levantar el teléfono / empezar a caminar | Significant Motion dispara GPS Espía; al cruzar ~25 m inicia tracking oficial |
| Entrar/salir de una geovalla dibujada en la web | `[GEOVALLA]` con `inside: true/false` |
| Con la app cerrada (recents) y GPS activo | sigue llegando `[LOCATION]` y la cola crece (`getPendingPointsCount`) |
| Reiniciar el teléfono con tracking activo | `BOOT_COMPLETED` relanza el Service (se requiere el gestor de arranque automático = `was_tracking`) |

### Trampas frecuentes

1. **"No llegan puntos":** el GPS no está en alta precisión, o el reposo está activo
   de un viaje anterior (mueve el teléfono 25 m+). Revisa los logs antes de culpar al módulo.
2. **La app muere en segundo plano:** batería del fabricante (Xiaomi/Huawei/Samsung) —
   el usuario debe desactivar la optimización de batería para la app; eso es externo
   al módulo.
3. **Emulador sin Google APIs:** `FusedLocationProviderClient` falla/da fixes falsos.
4. **Permisos de background revocados:** Android no lo re-pide; re-guía al usuario al
   panel de ajustes.

---

## 11. Base de datos SQLite (estructura)

El módulo no depende del storage de la app: guarda en su propia base SQLite
(con **WAL**, `PRAGMA wal_checkpoint(RESTART)` en los drenados) en el sandbox del
módulo — se borra sola si se desinstala la app. No hay `.db` compartido con
datos de usuario.

| Dato | Valor |
| --- | --- |
| Archivo | `isync_tracking.db` |
| Version | 4 (`DB_VERSION`) — migraciones al abrir |
| Modo | WAL (`enableWriteAheadLogging`) + checkpoint manual |
| Mapa TS | `StoredPoint` (`src/IsyncBackgroundLocation.types.ts`) |

### Tabla `location_points` — puntos capturados (cola de pendientes)

```sql
CREATE TABLE location_points (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  clientUuid   TEXT    NOT NULL,                    -- idempotencia ante el backend
  latitude     REAL    NOT NULL,
  longitude    REAL    NOT NULL,
  altitude     REAL,                                -- NULL si Location no trae dato
  speed        REAL,
  accuracy     REAL,
  heading      REAL,
  timestamp    INTEGER NOT NULL,                    -- epoch ms del proveedor
  sincronizado INTEGER DEFAULT 0,                   -- 0 pendiente / 1 subido (server 200)
  intentos     INTEGER DEFAULT 0                    -- intentos de subida (debug)
);
CREATE INDEX idx_location_points_timestamp ON location_points (timestamp);
```

| Columna | Tipo SQLite | Tipo JS (`StoredPoint`) | Notas |
| --- | --- | --- | --- |
| `id` | `INTEGER` (PK auto) | `number` | Orden de captura; el batch lo usa ascendente |
| `clientUuid` | `TEXT NOT NULL` | `string` | `UUID.randomUUID()` al insertar; clave de idempotencia |
| `latitude` / `longitude` | `REAL NOT NULL` | `number` | WGS-84 grados decimales |
| `altitude` | `REAL` | `number \| null` | `hasAltitude()` en el fix |
| `speed` | `REAL` | `number \| null` | `hasSpeed()` |
| `accuracy` | `REAL` | `number \| null` | `hasAccuracy()` |
| `heading` | `REAL` | `number \| null` | rumbo, `hasBearing()` - corresponde a `heading` JS |
| `timestamp` | `INTEGER NOT NULL` | `number` | epoch ms |
| `sincronizado` | `INTEGER DEFAULT 0` | `number` (opcional) | flag 0/1 |
| `intentos` | `INTEGER DEFAULT 0` | `number` (opcional) | contador de reintentos |

Ciclo de vida de una fila:

1. `insert(Location)` — captura el fix y genera `clientUuid` (**solo Android**).
2. `forEachRow(limit, cb)` — el `BatchSyncWorker` lee RAW y arma el protobuf
   (Post `/gps/batch`, lotes de 50).
3. `markSynced(ids)` — tras un **200** confirmado del server, `sincronizado = 1`.
4. `incrementAttempts(ids)` — en cada reintento (429/5xx/red) suma `intentos`.
5. `purge(maxRecords, maxAgeMillis)` — borra por antigüedad y por tope de volumen
   (chunks de 5000, máx. 100 pasadas por ejecución).
6. `deleteByIds(ids)` — borrado manual (exposición `deletePoints`).

**Ejemplo de fila** (columna / valor):

```text
id=1 | clientUuid="b7f…9d3" | latitude=19.4326 | longitude=-99.1332
altitude=2240.0 | speed=1.2 | accuracy=8.0 | heading=90.0
timestamp=1700000000000 | sincronizado=1 | intentos=2
```

### Tabla `geovallas` — cache local del FeatureCollection

```sql
CREATE TABLE geovallas (
  id          INTEGER PRIMARY KEY,                  -- id del Feature (server)
  nombre      TEXT    NOT NULL,
  descripcion TEXT,                                 -- NULL si el server no manda
  tipo        TEXT,
  activo      INTEGER NOT NULL DEFAULT 1,           -- flag 0/1
  rings       TEXT    NOT NULL,                     -- JSON [[[lng,lat],…],…]
  geojson     TEXT,                                 -- GeoJSON original, si existe
  updated     INTEGER NOT NULL                      -- epoch ms del último refresh
);
```

| Columna | Tipo SQLite | Tipo JS (`GeoValla`) | Notas |
| --- | --- | --- | --- |
| `id` | `INTEGER` (PK) | `number` | Autoritativo; cada GET exitoso **reemplaza** toda la tabla |
| `nombre` | `TEXT NOT NULL` | `string` | fallback `name` → `"Geovalla sin nombre"` |
| `descripcion` | `TEXT` | `string \| null` | |
| `tipo` | `TEXT` | `string \| null` | |
| `activo` | `INTEGER DEFAULT 1` | `boolean` | los inactivos no se evalúan |
| `rings` | `TEXT NOT NULL` | `GeoPoint[][]` | JSON string: los anillos viajan `[lng, lat]` en disco y se entregan al JS como `{latitude, longitude}` |
| `geojson` | `TEXT` | `string \| null` | copia del GeoJSON original si el server la trae |
| `updated` | `INTEGER NOT NULL` | `number` | epoch ms del último refresh |

**Ejemplo de fila**:

```text
id=7 | nombre="Zona A" | descripcion="Poligono de prueba" | tipo="indicador"
activo=1 | rings=[[[-99.1,19.1],[-99.1,19.5],[-98.5,19.5],[-98.5,19.1],[-99.1,19.1]]]
geojson=NULL | updated=1700000000000
```

**Ejemplo de consulta útil** (debug en `adb shell`):

```bash
# Puntos pendientes de subir, más antiguos primero
adb shell run-as <tu.package.id> sqlite3 databases/isync_tracking.db \
  "SELECT id, clientUuid, latitude, longitude, sincronizado, intentos FROM location_points ORDER BY timestamp ASC LIMIT 10;"

# Cuántas geovallas hay cacheadas y su estado
adb shell run-as <tu.package.id> sqlite3 databases/isync_tracking.db \
  "SELECT id, nombre, activo, length(rings) AS bytes_rings FROM geovallas ORDER BY id DESC;"
```

> Los tipos: SQLite es dinámico (TEXT/REAL/INTEGER); los `NULL` se expulsan al JS
> como `null` (no como `0`), igual que en `StoredPoint`/`GeoValla` tipados.

## 12. FAQ

**¿Necesito Expo Go?** No, es módulo nativo: dev build (`npx expo run:android`).

**Recibo "req&#x3C;iSyncLocationProvider&#x3E;" (error de gate).** Es la política del módulo:
validación de que la app está envuelta en `<IsyncLocationProvider>`. Monta el
Provider en la raíz (o en el árbol que use `BackgroundLocation.*`/hooks) y la
llamada se reanuda. El gate se activa al montar el Provider y se libera al
desmontarlo; soporta providers anidados.

**¿Funciona en iOS?** No. El paquete es solo Android (`expo-module.config.json` →
`["android"]`). Importarlo en iOS/otra plataforma no registra el módulo.

**¿El tracking depende de que la app esté abierta?** No. Foreground Service + cola
SQLite + WorkManager: sobrevive a app cerrada, proceso muerto y reboot.

**¿Puedo controlar el intervalo?** Sí, `start({ interval })` (ms). Default 5 s; el
pipeline además filtra por desplazamiento y reposo, así que el consumo no escala
lineal con intervalos agresivos.

**¿Qué pasa si no hay red?** Los puntos se acumulan en SQLite (WAL). Cuando vuelve la
red, el batch periódico (10 min) y el one-shot drenan la cola; el realtime se
reconecta solo.

---

## 13. Referencia rápida de la API

| Método | Retorna |
| --- | --- |
| `requestPermissions()` | `Promise<PermissionResponse>` |
| `start(options?)` / `stop()` | `Promise<null>` |
| `getStatus()` | `Promise<{ available, isRunning }>` |
| `getCurrentLocation()` | `Promise<LocationEventPayload>` |
| `getMotionState()` | `Promise<MotionSensorState>` |
| `addLocationListener(fn)` / `addGeoVallasListener(fn)` | `Subscription` |
| `getGeoVallas()` / `refreshGeoVallas()` | `Promise<GeoValla[]>` |
| `setGeovallaNotificationsEnabled(b)` / `getGeovallaNotificationsEnabled()` | `Promise<null>` / `Promise<boolean>` |
| `getPendingPoints(limit)` / `getPendingPointsCount()` | `Promise<StoredPoint[]>` / `Promise<number>` |
| `deletePoints(ids)` | `Promise<null>` |
| `startBatchSync()` / `stopBatchSync()` | `Promise<null>` |

Doc completa de tipos y comportamiento: `README.md`. Notas de ingeniería e historia:
`docs/ENGINEERING.md`.