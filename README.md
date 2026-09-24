# iSync Background Location Module

## Geovallas (Etapa 3) — `/api/geovallas`

API de geovallas poligonales (RFC 7946; el ciclo la resume `docs/Geovallas.md`).
El módulo mantiene el ciclo completo en nativo, sin depender del puente JS:

- **`GeoVallasServices.kt`** (modelo + red + detección unificados) — cliente
  OkHttp contra `https://isync-tracker-ws.vercel.app/api/geovallas` (misma URL
  para localdev y producción, acordado). `refresh()` baja el FeatureCollection y
  reemplaza la cache. **Solo consume**: no hay `POST`/`create` — la escritura de
  geovallas la hace la techweb. Incluye el modelo (`GeoValla`/`GeoPoint`), el
  parseo de la respuesta (`[lng, lat]` → lat/lng) y el point-in-polygon por ray
  casting.
- **Tabla `geovallas` en `isync_tracking.db`** (`TrackingDatabase`, v4) —
  cache durable: sobrevive al kill del proceso y a un arranque sin red
  (`boot()` la carga a memoria al arrancar el Service).
- **Detección** — cada fix aceptado por el pipeline de captura se evalúa en el
  `workerLooper` contra las geovallas activas; al cruzar un borde se emite el
  evento `geovalla` (`inside: true/false`) hacia JS. El primer fix solo reporta
  enter (la UI aprende el estado inicial sin spamear exits).
- **Notificación de entrada (background)** — el `GeovallaNotifier` (vive en
  el mismo `GeoVallasServices.kt`) postea una
  notificación heads-up al **entrar** en una geovalla: canal `geovalla_events`
  (importancia alta) cuyo sonido es `res/raw/location.mp3` (el mismo
  `assets/sound/location.mp3`, copiado al res del módulo para que suene sin JS).
  Es **opcional** desde JS (`setGeovallaNotificationsEnabled`, flag
  `geovalla_notifications` en SharedPreferences; el evento `geovalla` a JS
  nunca se silencia).
  Todo corre en el `workerLooper` del Service, en segundo plano.
- **Carga única al arrancar** — el FeatureCollection se baja **una vez** con el
  `refresh()` al arrancar el Service (no hay refresco periódico: se eliminó el
  del mantenimiento). El JS solo lee la cache (`getGeoVallas()`) desde
  `tracking.tsx` / `settingsHost.tsx`; `refreshGeoVallas`/`createGeoValla` se
  eliminaron (no se usaban: el consumo es interno al nativo).

## Historial de Refactorización y Optimización Crítica (Sep 2026)

### Consolidación de geovallas (Sep 2026)
`GeoVallas.kt` (modelo/parseo) y `GeoVallasService.kt` (red/detección) se
fusionaron en un único `GeoVallasServices.kt` por pedido explícito: mismo
package, cero imports entre ellos, la separación no aportaba. API pública y
ciclo de vida intactos.

### Modo solo-consulta de geovallas (Sep 2026)
Por pedido explícito se quitó toda escritura: `create()` (POST, nativo),
`createGeoValla` y `refreshGeoVallas` (superficie JS + AsyncFunctions) y los
items debug de `settingsHost.tsx` que las usaban. Queda el ciclo
`GET /api/geovallas` → tabla `geovallas` (start + mantenimiento) + lectura de
cache (`getGeoVallas`) + detección/evento. Al no haber más POST, `parseCreateId`,
`RequestBody` y `MediaType` se eliminaron del archivo.

### Notificación de entrada a geovallas (Sep 2026)
Al detectar una entrada (`evaluate()` en el workerLooper) se postea además una
notificación nativa vía el `GeovallaNotifier` (fusionado en `GeoVallasServices.kt`):
canal `geovalla_events`
(IMPORTANCE_HIGH) con sonido `res/raw/location.mp3` (copia del
`assets/sound/location.mp3`), PendingIntent que abre la app, vibrate y
`POST_NOTIFICATIONS`/`VIBRATE` agregados al manifest del módulo. `requestPermissions`
ya pedía `POST_NOTIFICATIONS`, no hizo falta cambiarlo.

### Notifier + Servicios fusionados (Sep 2026)
`GeovallaNotifier.kt` se fusionó dentro de `GeoVallasServices.kt` (un solo
archivo con el modelo, la red, la detección y las notificaciones), por pedido
explícito — mismo package, un solo referente de la etapa 3.

### Notificaciones de geovalla opcionales desde JS (Sep 2026)
`setGeovallaNotificationsEnabled`/`getGeovallaNotificationsEnabled` en la
superficie JS escriben/leen `geovalla_notifications` (SharedPreferences,
default `true`). El notifier consulta el flag antes de postear; el evento
`geovalla` hacia JS no cambia.

### Baja de la geovalla de reposo (Sep 2026)
Se **eliminó el geofence circular de reposo** (`GeofencingClient`,
`GeofenceTransitionReceiver`, toggle `stationaryGeofenceEnabled`, superficie JS
`getStationaryGeofence`/`setStationaryGeofence`) porque no se usaba en
producción. El reposo sigue igual (IMU + ancla + Activity Recognition + GPS
Espía); lo que se perdió es el wake por broadcast **con proceso muerto** (ahora
solo IMM lo cubre). Especificación de pies a cabeza para reimplementarla a
futuro: **`docs/geovalla-reposo-removida.md`**.

### El Problema de Sobrecalentamiento y Consumo de Batería (Ataque DDoS Interno)
Anteriormente, la aplicación registraba métricas insostenibles en producción:
- **Tiempo de CPU:** ~8 horas y 21 minutos (en un lapso de 14 horas).
- **Tráfico Wi-Fi:** ~32,687,932 paquetes enviados.
- **Activaciones Injustificadas (Falsos Positivos):** 48 activaciones del GPS.
- **Líneas Rectas en el Mapa:** Puntos de rastreo tardíos que atravesaban propiedades privadas después de abandonar la geovalla.

#### Causa Raíz
1. **Bucle Infinito de Reconexión (DDoS):** En `LocationRelay.kt`, se utilizaba `ConnectivityManager.NetworkCallback` para forzar la reconexión manual de Socket.io. Al destruir y recrear el socket repetidamente dentro del callback `onAvailable`, el sistema operativo generaba una cascada infinita de llamadas asíncronas, resultando en millones de paquetes intentando hacer el handshake del WebSocket y sobrecargando la CPU.
2. **Alta Precisión en Reposo:** El GPS solicitaba ubicación cada 2 segundos (`interval = 2000`) sin apagarse inmediatamente después de falsos positivos de movimiento (por ejemplo, mover el teléfono en la mesa requería esperar 5 minutos para que el GPS se durmiera).
3. **Destrucción Prematura de Geovalla:** El sistema destruía la geovalla basándose puramente en 15 segundos de datos del acelerómetro crudo, enviando un punto artificial en la posición de parqueo, e iniciando el GPS tarde, resultando en rutas cortadas.

### Soluciones Implementadas

#### 1. Eliminación del NetworkCallback (Gestión Nativa de Socket.io)
Se eliminó por completo el uso de `ConnectivityManager.NetworkCallback` en `LocationRelay.kt`. 
**Lección aprendida:** La librería `socket.io-client` maneja sus propias políticas de reconexión automática (`reconnection = true`, `reconnectionDelay`, etc.) de forma óptima en el hilo de red. Forzar una conexión de forma manual e interceptar eventos de red del sistema choca con el ciclo de vida del socket y causa bucles infinitos.

#### 2. Lógica de Rastreo Tentativo ("GPS Espía")
Para solucionar la línea recta y los falsos positivos:
- Ahora, con solo 5 segundos de movimiento (`armMovementConfirmTimer`), el GPS se enciende en modo "Tentativo". **El reposo NO se cancela**.
- Al recibir puntos de GPS, se mide la distancia respecto al "ancla" de parqueo.
- **Si distancia > 25m:** Se confirma el desplazamiento y se inicia el tracking oficial exacto desde la salida del estacionamiento.
- **Si distancia < 25m (falso positivo):** Al detenerse el movimiento, el GPS se apaga **inmediatamente**, ahorrando los 5 minutos de batería que se gastaban antes.

#### 3. Optimización del Intervalo del GPS
Se incrementó el `interval` base de la petición de `FusedLocationProviderClient` de 2000ms a **5000ms**. Esta cadencia de 5 segundos es el balance perfecto para capturar curvas cerradas sin mantener la antena GPS al 100% de potencia constante.

#### 4. Uso Abusivo de Sensores de Hardware y Falta de "Batching"
El detector de movimiento mantenía el **Acelerómetro, Giroscopio y Magnetómetro** encendidos 24/7 sin latencia de reporte.
- **Solución:** Se eliminó el registro del giroscopio y magnetómetro (innecesarios para detectar si el usuario camina o maneja, el acelerómetro basta). 
- **Deep Sleep:** Se configuró un `maxReportLatencyUs` de 2,000,000 (2 segundos) en el acelerómetro. Esto permite al procesador (CPU) del teléfono entrar en Deep Sleep y solo despertar 1 vez cada 2 segundos para procesar los movimientos en bloque, en lugar de despertar 5 veces por segundo ininterrumpidamente.

#### 5. Migración a Arquitectura 100% Basada en Eventos (Significant Motion)
Se reemplazó completamente el acelerómetro continuo por `Sensor.TYPE_SIGNIFICANT_MOTION` (`TriggerEventListener`), que corre en el Sensor Hub (microprocesador dedicado de microamperios).
- **CPU en reposo = 0% de uso:** El procesador principal duerme por completo hasta que el hardware detecta movimiento real.
- **Evento único por disparo:** El sensor se desactiva solo tras dispararse. Se re-arma manualmente tras cada decisión.
- **El GPS es el árbitro (sin timers):** Tras el disparo del sensor, el GPS Espía se enciende. Cada punto GPS evalúa velocidad y distancia vs. el ancla: `distancia > 25m` → viaje real (salir del reposo); `speed < 1.5 m/s` dentro del radio → falso positivo (apagar GPS, re-armar sensor). No se usa ningún timer para esta decisión.
- **Fallback automático:** Si el hardware del dispositivo no soporta `TYPE_SIGNIFICANT_MOTION`, el sistema usa `ActivityRecognition` de Google como disparador alternativo sin interrumpir el servicio (solo con el proceso vivo; con el proceso muerto nada lo despierta sin IMM — ver `docs/geovalla-reposo-removida.md`).
- **`motionGatePasses()` refactorizado:** Ahora usa exclusivamente la velocidad del GPS (`location.speed`) como criterio. Se eliminó la dependencia del acelerómetro en el filtrado de puntos.

#### 6. Corrección del Umbral de Velocidad GPS (Bug Post-Refactorización)
Al eliminar el acelerómetro del `motionGatePasses()`, el umbral de `1.5 m/s` (5.4 km/h) quedó como único criterio de movimiento. La velocidad típica caminando es `1.0 a 1.4 m/s` (3.6 a 5 km/h), por lo que **todos los puntos generados caminando eran descartados silenciosamente**.
- **Solución:** Se bajó `GPS_MOTION_THRESHOLD_MPS` de `1.5f` a **`0.5f`** (1.8 km/h). El GPS drift (jitter con el teléfono inmóvil) reporta velocidades de `0.0 a 0.3 m/s`; caminar reporta `1.0+`. El nuevo umbral filtra el drift pero permite todas las formas de desplazamiento humano.
- **Lección:** Al eliminar un sensor que servía como "bypass" de un gate, revisar TODOS los umbrales que dependían de ese bypass.
