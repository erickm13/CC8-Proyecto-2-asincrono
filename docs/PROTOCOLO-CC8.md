# Protocolo RAPID
### *Reliable Adaptive Progressive Image Delivery*
**Documento de especificación del protocolo — Proyecto 2 · Ciencias de la Computación VIII (Redes)**

Autor: **Erick Eleazar Mejía Moscoso**
Servidor: **Java 21** · Cliente: **HTML/CSS/JS** · Sin dependencias externas en
ejecución. El preprocesamiento ZIP opcional requiere `libvips`.

---

## 0. Resumen ejecutivo

**RAPID** es un protocolo de nivel de aplicación para transmitir imágenes de ultra
alta resolución (decenas de gigabytes) a un navegador web, entregando **solo la
información que el usuario necesita para lo que está mirando** y **refinándola de
forma progresiva** hasta la máxima definición requerida (los números de la imagen
deben leerse con nitidez).

El protocolo se divide en dos planos:

| Plano | Responsabilidad | Sección |
|-------|-----------------|---------|
| **Sustrato de imagen** (H2K o Deep Zoom ZIP) | *Qué información existe*: H2K ofrece niveles, precincts y capas de calidad; ZIP ofrece tiles PNG por niveles de resolución. | §3 |
| **Transporte RAPID** | *Cómo se transmite de forma confiable y eficiente*: ventana deslizante, SEQ/ACK/**SACK**, **Selective Repeat**, modos de scheduler, control de flujo y despacho justo entre sesiones. | §4–§8 |

El transporte adapta los mecanismos de **TCP (RFC 9293)** al plano de aplicación,
corriendo **sobre WebSocket (RFC 6455)**. La comunicación inicial (HTML, JS, CSS)
usa HTTP; a partir del *upgrade* a WebSocket, todo el intercambio de imagen usa el
formato binario propio de RAPID.

---

## 1. Objetivo y planteamiento del problema

Una imagen de 55 GB no puede transferirse completa: saturaría el ancho de banda,
la memoria del navegador y enviaría datos que el usuario nunca verá. RAPID resuelve
esto con **transferencia y eliminación selectiva de información**:

- Se transmite primero una versión **borrosa pero completa** y se **refina** la zona
  visible conforme el usuario la observa (aumento de resolución = *más* datos).
- Cuando necesita liberar caché, el cliente **desaloja tiles completos** y notifica al
  servidor con `FORGET`; la selección de paquetes sigue siendo progresiva.
- La entrega es **confiable, controlada y priorizada**: nunca se deja al usuario
  desatendido en la zona que solicita a máxima definición.

Esto **no** es una galería ni un *zoom-in* de imágenes pre-generadas: existe
transferencia y eliminación real de información gobernada por el protocolo.

---

## 2. Arquitectura y pila de protocolos

```
        NAVEGADOR (cliente)                     SERVIDOR (Java, asíncrono)
  ┌─────────────────────────────┐        ┌───────────────────────────────┐
  │  UI: canvas, zoom/pan        │        │  Preprocesador H2K / libvips  │
  │  DWT o decodificador PNG     │        │  H2kReader / ZipPyramidReader │
  │  Receptor RAPID (ACK/SACK)   │        │  Scheduler (tres modos RAPID)  │
  │  Caché LRU de tiles          │        │  Emisor RAPID (ventana, cc)    │
  └──────────────┬──────────────┘        └───────────────┬───────────────┘
                 │  Frames binarios RAPID (DATA / ACK / control)
                 │  ─────────────  WebSocket (RFC 6455)  ─────────────
                 │  HTTP/1.1 (solo para archivos iniciales y el upgrade)
                 └───────────────────────────────────────────────────────
                            TCP  /  IP   (provisto por el S.O.)
```

**Establecimiento:**

1. El navegador pide `GET /` por HTTP → recibe HTML/CSS/JS servidos por el propio
   servidor Java (ningún recurso externo).
2. El JS abre `GET /stream` con cabeceras `Upgrade: websocket` → el servidor
   responde `101 Switching Protocols` (handshake RFC 6455).
3. A partir de ahí corre **RAPID** en frames binarios de WebSocket.

### 2.1 ¿Qué aporta RAPID sobre WebSocket/TCP?

WebSocket usa un único flujo TCP ordenado. Si TCP espera bytes perdidos o retrasados,
los mensajes WebSocket posteriores también esperan; RAPID no evita ese bloqueo ni
puede entregar frames fuera del orden del flujo.

RAPID añade identidad y ACK/SACK por DATA, con reintento selectivo a nivel de
aplicación para DATA no confirmados. En producción, TCP sigue recuperando la pérdida
de red; RAPID no la observa directamente ni puede adelantar mensajes en el flujo.
RAPID sí prioriza datos aún no enviados cuando cambia el viewport y expone presión de
caché mediante `rwnd` y `FORGET`. Las pruebas de pérdida usan un enlace simulado.

---

## 3. Sustratos de imagen: H2K v1 y Deep Zoom ZIP

> *H2K v1 es el formato original y permanece sin cambios. Las subsecciones 3.1–3.3
> describen su representación; §3.4 documenta la alternativa ZIP Deep Zoom.*

### 3.1 Descomposición

Cada imagen se preprocesa **una sola vez, por tiles**, sin cargarla completa en RAM:

1. **Tiles**: la imagen se parte en bloques de `512×512` (potencia de dos).
2. **DWT Haar reversible** (transformada S entera, sin pérdida) con `5` niveles →
   produce **niveles de resolución** `R0` (LL más gruesa, `16×16`) … `R5` (detalle
   más fino). Referencia: transformada 5/3 reversible de **ISO/IEC 15444-1
   (JPEG2000)** y Calderbank et al., *"Wavelet transforms that map integers to
   integers"*.
   ```
   Forward (por par a0,a1):  d = a1 − a0 ;  s = a0 + ⌊d/2⌋
   Inverse (por par s,d):    a0 = s − ⌊d/2⌋ ;  a1 = a0 + d
   ```
3. **Precincts**: cada nivel de resolución se subdivide en regiones de `64×64` →
   unidad de **selección espacial**.
4. **Capas de calidad (bit-planes)**: los coeficientes de cada precinct se codifican
   por **planos de bits**, del más significativo (MSB) al menos (LSB), con **signo
   perezoso** (el bit de signo se emite cuando el coeficiente se vuelve significativo,
   como en JPEG2000). Cada plano = una *quality layer*. Recibir más planos = más
   nitidez; descartar planos = menos resolución. Cada paquete se comprime con
   **DEFLATE** (RFC 1951, incluido en el JDK; en el navegador se descomprime con
   `DecompressionStream('deflate-raw')`).

Así, la unidad mínima direccionable es el **paquete**:
`(tile, componente, nivel de resolución, precinct, capa)`.

### 3.2 Layout del archivo `.h2k`

```
┌────────────┬─────────────────────────────┬───────────────────────────┬───────────────┐
│ Header 64B │ Región de paquetes (DEFLATE)│ Índices por tile (interc.)│ Directorio de │
│            │  intercalada con los índices│                           │ tiles         │
└────────────┴─────────────────────────────┴───────────────────────────┴───────────────┘
```

**Header (64 bytes):**

| Offset | Campo | Bytes | Descripción |
|-------:|-------|:-----:|-------------|
| 0 | `magic` | 4 | `"H2K1"` |
| 4 | `version` | 1 | 1 |
| 5 | `colorTransform` | 1 | 0 = ninguna |
| 6 | `components` | 1 | 1 (gris) o 3 (RGB) |
| 7 | `bitDepth` | 1 | 8 |
| 8 | `tileSize` | 4 | 512 |
| 12 | `levels` | 4 | 5 |
| 16 | `precinct` | 4 | 64 |
| 20 | `width` | 4 | ancho en px |
| 24 | `height` | 4 | alto en px |
| 28 | `tilesX` | 4 | tiles por fila |
| 32 | `tilesY` | 4 | tiles por columna |
| 36 | `tileDirOffset` | 8 | offset al directorio de tiles |
| 44 | *reservado* | 20 | |

El **directorio de tiles** lista, por cada tile, `(offset, longitud)` de su bloque de
índice. El servidor solo lee el índice del tile que necesita → apto para archivos de
50 GB+ (el índice completo nunca se carga de una vez). El servicio usa lectura
posicional (`FileChannel.read(buf, pos)` / `mmap` por región).

### 3.3 Vista general (overview / thumbnail)

El preprocesador también genera un **overview**: el bloque LL (la sub-banda más
gruesa, `s0×s0`) de cada tile, ensamblado en una miniatura de toda la imagen a
resolución `1/2^levels` (p.ej. 1/32). Se guarda como PNG al final del `.h2k` (campos
`overviewOffset/Len/W/H` en el header) y se sirve por **HTTP `GET /api/overview`** en
una sola petición. El cliente lo pinta como capa base **al instante** cuando la vista
está alejada, y solo solicita tiles por WebSocket cuando el usuario se acerca lo
suficiente. Así, explorar una imagen de 93 GB nunca enumera sus decenas de miles de
tiles: la vista alejada usa el overview; la cercana, unos pocos tiles.

### 3.4 Pirámide Deep Zoom PNG en ZIP

`preprocess_vips.sh entrada.png salida.zip` usa el comando `vips` de libvips
para generar un ZIP (incluido ZIP64) con descriptor DZI y tiles PNG sin pérdida.
El lector Java usa `ZipFile` para acceder al archivo sin extraerlo. La configuración
predeterminada usa tiles de 512 px, `overlap=0`, `depth=onetile`, PNG compression
0 y ZIP sin compresión externa; `--tile-size=N` y `--png-compression=0..9`
permiten ajustar la salida. El comando `vips` debe estar en `PATH` o definirse
con `VIPS_BIN`.

El nivel 0 es un único tile de overview servido por `GET /api/overview`. El servidor
indexa las entradas del ZIP para leer tiles por nivel y coordenadas; el cliente
ensambla cada PNG al recibir todos sus fragmentos. Esta representación
ofrece niveles de resolución, pero no las capas de calidad por bit-plane de H2K.
H2K v1 sigue sin cambios y ambos formatos funcionan con las tres políticas RAPID.
Cambiar el modo no requiere regenerar la imagen.

---

## 4. Transporte RAPID: modelo general

- **Dirección de datos:** servidor → cliente (mensajes **DATA**). El cliente confirma
  con **ACK** (acumulativo + SACK) y anuncia su ventana de recepción.
- **Unidad de secuencia:** un frame **DATA**. Puede contener un paquete H2K, un
  fragmento ZIP `ZT`, o en modos por lotes hasta 16 unidades de imagen. ACK/SACK,
  retransmisión y secuencia cuentan DATA, no los elementos del lote.
- **Espacio de secuencia:** entero de 32 bits, monótono creciente dentro de una
  sesión; en los modos por lotes, cada secuencia identifica el DATA completo, no cada
  paquete que contiene.
- **Entrega a la aplicación:** WebSocket entrega sus mensajes en el orden del flujo
  TCP. Al recibir un DATA, el cliente puede procesar sus paquetes autodescriptivos;
  RAPID mantiene ACK/SACK por DATA y reprioriza datos aún no enviados, pero no evita
  el head-of-line blocking de TCP.

---

## 5. Formato de los mensajes (frames RAPID)

Todos los enteros son **big-endian**. El primer byte es el **tipo**. Los mensajes
viajan como *payload* de un frame binario de WebSocket.

### 5.1 Tabla de tipos

| Tipo | Valor | Dirección | Propósito |
|------|:-----:|-----------|-----------|
| `HELLO` | 1 | C → S | Negociar versión y modo |
| `DATA` | 2 | S → C | Segmento de datos; puede contener un lote |
| `ACK` | 3 | C → S | ACK acumulativo + SACK + `rwnd` + eco de timestamp |
| `VIEWPORT` | 4 | C → S | Cambio de zona/zoom visibles (repriorización) |
| `WIN` | 5 | — | Reservado; la ventana se anuncia en ACK |
| `FORGET` | 6 | C → S | El cliente desalojó tiles; el scheduler puede reprogramarlos si reaparecen |
| `FIN` | 7 | ambos | Cierre ordenado de la sesión |

### 5.2 DATA (servidor → cliente)

```
 0      1                    5                            13     14         16
 ┌──────┬────────────────────┬────────────────────────────┬──────┬──────────┬─────────…
 │ type │        seq         │          sendTs            │flags │payloadLen│ payload…
 │  =2  │      (uint32)      │        (uint64, ms)        │(u8)  │ (uint16) │
 └──────┴────────────────────┴────────────────────────────┴──────┴──────────┴─────────…
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `type` | u8 | 2 |
| `seq` | u32 | Número de secuencia del segmento |
| `sendTs` | u64 | Marca de tiempo de envío (ms). El cliente la refleja en el ACK para medir RTT |
| `flags` | u8 | bit0 = **RETX**; bit1 = **BATCH** (lote de paquetes) |
| `payloadLen` | u16 | Longitud del payload |
| `payload` | bytes | Paquete H2K (§5.6), fragmento ZIP (§5.7), o lote `[longitud(u16), unidad]*` si está activo `BATCH` |

El modo legacy deja `BATCH` apagado; los modos 1 y 2 habilitan el agrupamiento. Cada
DATA marcado `BATCH` lleva hasta **16** unidades, cada longitud es `u16` big-endian y
el payload no supera **32.768 bytes**. Una unidad que no cabe en el límite se envía
sola. En ZIP, cada unidad es un fragmento `ZT` con hasta 16.000 bytes de PNG. ACK y
rangos SACK siguen refiriéndose al `seq` de cada DATA; la retransmisión conserva
su `seq`, flags y payload.

### 5.3 ACK (cliente → servidor)

```
 0      1            5            9                        17     18        26 …
 ┌──────┬────────────┬────────────┬────────────────────────┬──────┬─────────┬─────────…
 │ type │    ack     │    rwnd    │        echoTs          │nSack │ SACK[0] │ SACK[1]…
 │  =3  │  (uint32)  │  (uint32)  │      (uint64, ms)      │ (u8) │ 8 bytes │
 └──────┴────────────┴────────────┴────────────────────────┴──────┴─────────┴─────────…

 Cada bloque SACK:  ┌────────────┬────────────┐
                    │  start(u32)│  end(u32)  │   rango half-open [start, end)
                    └────────────┴────────────┘
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `type` | u8 | 3 |
| `ack` | u32 | ACK **acumulativo**: siguiente SEQ esperado (todos los `< ack` recibidos) |
| `rwnd` | u32 | Ventana de recepción anunciada, en **segmentos** (flow control) |
| `echoTs` | u64 | `sendTs` del segmento que disparó este ACK (para RTT) |
| `nSack` | u8 | Número de bloques SACK (≤ 4) |
| `SACK[i]` | 2×u32 | Rangos `[start, end)` recibidos **fuera de orden** (RFC 2018) |

### 5.4 VIEWPORT (cliente → servidor)

```
 type=4 │ x(u32) │ y(u32) │ w(u32) │ h(u32) │ zoom(u8) │ [dx(i8) │ dy(i8) │ confidence(u8)]
```
La forma base ocupa **18 bytes** y contiene la zona visible y el nivel máximo de
resolución. `RAPID Predictivo DRR` añade `dx`, `dy` y `confidence` para un total de
**21 bytes**: dirección normalizada en `[-127,127]` y confianza en `[0,255]`. Un
cambio de vista reconstruye la cola de paquetes pendientes.

### 5.5 HELLO / FORGET / WIN / FIN

- **FORGET**: `type=6 │ count(u16) │ [tile(u32)]*` — el cliente informa qué tiles
  desalojó de su caché (LRU). El servidor vuelve a hacer elegibles los paquetes de
  esos tiles para un envío posterior.
- **HELLO**: `type=1 │ version(u8) │ mode(u8)` — exactamente 3 bytes; modos `0..2`.
  Versión `1` selecciona H2K; versión `2` selecciona ZIP Deep Zoom. H2K permite
  omitir `HELLO` y conserva el modo legacy `0`; una sesión ZIP requiere HELLO v2.
- **WIN** (`type=5`) está reservado; el cliente anuncia `rwnd` en cada ACK.
- **FIN**: `type=7` — cierre ordenado.

### 5.6 Payload H2K de DATA: paquete de imagen

```
 tile(u32) │ comp(u8) │ level(u8) │ py(u16) │ px(u16) │ layer(u8) │ numPlanes(u8) │ numCoeffs(u32) │ dataLen(u16) │ deflate(bit-plane)…
```
Cabecera de **18 bytes**, seguida por el plano comprimido. El cliente usa los campos
de tile, componente, nivel, precinct y capa junto con `numPlanes` y `numCoeffs` para
reconstruir por inverse DWT.

### 5.7 Payload ZIP de DATA: fragmento de tile `ZT`

Cada fragmento comienza con una cabecera de **22 bytes**; todos los enteros son
big-endian. A continuación van hasta **16.000 bytes** contiguos del PNG del tile.

| Offset | Campo | Tipo | Descripción |
|-------:|-------|------|-------------|
| 0 | `magic` | 2 bytes | ASCII `ZT` |
| 2 | `version` | u8 | 1 |
| 3 | `tileId` | u32 | Identificador RAPID del tile |
| 7 | `level` | u8 | Nivel de resolución DZI |
| 8 | `tx`, `ty` | u16, u16 | Coordenadas del tile dentro del nivel |
| 12 | `part`, `parts` | u16, u16 | Índice desde 0 y cantidad de fragmentos |
| 16 | `totalBytes` | u32 | Tamaño completo del PNG del tile |
| 20 | `dataLen` | u16 | Bytes PNG en este fragmento |
| 22 | `data` | bytes | Segmento PNG; máximo 16.000 bytes |

El cliente reensambla por `tileId` y `part`, valida el tamaño total y decodifica el
PNG cuando llegan todos los fragmentos. La confiabilidad y retransmisión siguen
siendo las de DATA, con ACK/SACK por número de secuencia.

---

## 6. Mecanismos de confiabilidad y control (el núcleo)

Adaptados de **RFC 9293 (TCP)**, **RFC 5681 (control de congestión)**,
**RFC 2018 / 6675 (SACK)** y **RFC 6298 (RTO)**.

### 6.1 Ventana deslizante

En todo momento hay como máximo **`min(cwnd, rwnd)`** segmentos *en vuelo*:

```
  … confirmados │  en vuelo (≤ min(cwnd,rwnd)) │  aún no enviados │ …
  ──────────────┼──────────────────────────────┼──────────────────►  seq
             sndUna                          sndNxt
```
- `sndUna`: menor SEQ sin confirmar. `sndNxt`: siguiente SEQ a asignar.
- `en_vuelo = (sndNxt − sndUna) − |SACKed|` (los segmentos con SACK no ocupan el pipe).
- `cwnd` (congestión) y `rwnd` (flujo, anunciada por el cliente) acotan la ventana.

### 6.2 ACK acumulativo + SACK, y Selective Repeat

- El receptor entrega cada payload apenas llega (dedup) y mantiene `rcvNxt` (borde
  acumulativo) y el conjunto de SEQ fuera de orden → **bloques SACK** contiguos.
- El emisor, con `ack` + bloques SACK, sabe **exactamente qué falta** y retransmite
  **solo eso** (Selective Repeat), no un rango completo (a diferencia de Go-Back-N).
- **Regla *IsLost* (RFC 6675):** un hueco se considera perdido (no mero
  reordenamiento) cuando hay **≥ 3 segmentos con SACK por encima** de él. Esto evita
  retransmisiones espurias ante reordenamiento leve.

### 6.3 Control de congestión (estilo Reno)

```
        cwnd
          ▲
          │            /\        (fast recovery)
 ssthresh ┤ - - - - - /  \ - - - - - - - -
          │          /    \___/‾‾‾  (congestion avoidance: +1/RTT)
          │   (slow  /
          │   start)/  ← ×2 por RTT
        1 ┤________/________________________► tiempo
                 timeout → cwnd=1
```

| Fase | Condición | Regla de `cwnd` |
|------|-----------|-----------------|
| **Slow start** | `cwnd < ssthresh` | `cwnd += 1` por ACK (crecimiento exponencial, ×2/RTT) |
| **Congestion avoidance** | `cwnd ≥ ssthresh` | `cwnd += 1/cwnd` por ACK (lineal, +1/RTT) |
| **Fast retransmit/recovery** | 3 ACK duplicados | `ssthresh = máx(en_vuelo/2, 2)`, `cwnd = ssthresh+3`, retransmite el hueco; sale al confirmar `recoverPoint` |
| **Timeout (RTO)** | vence el temporizador | `ssthresh = máx(en_vuelo/2, 2)`, `cwnd = 1`, backoff `RTO×2`, retransmite `sndUna` |

Valores iniciales: `cwnd = 1`, `ssthresh = 64` segmentos.

### 6.4 Estimación de RTT y RTO (RFC 6298 + Karn)

```
 SRTT   = 7/8·SRTT + 1/8·muestra
 RTTVAR = 3/4·RTTVAR + 1/4·|SRTT − muestra|
 RTO    = clamp( SRTT + 4·RTTVAR , 200 ms , 60 s )
```
- **Algoritmo de Karn:** no se toma muestra de RTT de segmentos retransmitidos
  (ambigüedad). RAPID sí muestrea de segmentos confirmados por SACK que se enviaron
  una sola vez, obteniendo RTT válido incluso bajo pérdida.
- **Backoff exponencial:** cada timeout duplica el RTO (hasta 60 s); una muestra
  válida lo reajusta.

### 6.5 Control de flujo (flow control)

El cliente calcula `rwnd` según el headroom de caché y el backlog de decodificación,
y lo anuncia en cada ACK. El rango es **8–256 DATA** en modo legacy y **1–16 DATA**
en modos 1/2; cada DATA por lotes puede contener hasta 16 paquetes. En el modo 2 el
headroom usa el límite de 256 MiB; los otros modos usan 160 tiles.

---

## 7. Modos de scheduler

El selector del visor ofrece estos nombres y valores de `HELLO.mode`:

| Modo | Política |
|------|----------|
| `0 — RAPID Progresivo (Hilbert)` | Legacy: progresión espacial por Hilbert, sin lotes. Una conexión que omite `HELLO` usa este modo. |
| `1 — RAPID Cobertura EDF` | Prioriza nivel grueso visible, luego capa base visible y después el detalle; los tiles vecinos van al final. Mide vencimientos de envío de 250 ms para nivel grueso y 1000 ms para las demás capas base visibles, desde el último `VIEWPORT`. Con carga ≥0,7 difiere prefetch; con carga ≥0,85 difiere capas de detalle visibles. |
| `2 — RAPID Predictivo DRR` | Prioriza nivel grueso y datos visibles; agrega una franja de un tile en la dirección del paneo. Confianza 0 no agrega franja; la confianza determina su extensión transversal. Un bucket de tokens limita el prefetch a 65.536 bytes, con reposición de 65.536 bytes/s. Con carga ≥0,7 difiere prefetch y con ≥0,85 difiere detalle visible. |

La carga es una estimación compartida que llega a 1 con 16 sesiones. Los vencimientos
son contadores observables cuando el scheduler obtiene una capa base visible después
de su plazo; son métricas de despacho, no garantías de latencia de red. Los tres
modos funcionan con H2K y ZIP: cambiar el selector reabre la sesión y cambia la
planificación, sin regenerar la imagen.

El dispatcher aplica **DRR por bytes entre todas las sesiones con trabajo pendiente**:
cada ronda añade un quantum de hasta 32.832 bytes por sesión. La cola de escritura de
cada sesión WebSocket admite como máximo **1 MiB**; al llenarse, difiere el envío sin
consumir el siguiente número de secuencia.

---

## 8. Máquina de estados y flujos

### 8.1 Estados de la sesión

```
   CLOSED ──HELLO(v1/v2) o H2K legacy──► OPEN ──(DATA/ACK, VIEWPORT, FORGET)──► OPEN
                        │                                            │
                        └───────────────── FIN ──────────────────► CLOSING ─► CLOSED
```

### 8.2 Diagrama de secuencia (apertura + refinamiento + cambio de zona)

```
 Cliente                                Servidor
   │  HTTP GET / (HTML,JS,CSS)  ───────────►│
   │◄───────── 200 OK  ─────────────────────│
   │  GET /stream  Upgrade: websocket ─────►│
   │◄──────── 101 Switching Protocols ──────│
   │  HELLO(version=1 H2K / 2 ZIP, mode) ──►│ (ZIP exige HELLO; H2K omite = modo 0)
   │  VIEWPORT(x,y,w,h,zoom) ──────────────►│  scheduler prioriza
   │◄── DATA seq=0 (paquete o lote) ────────│  cwnd=1 (slow start)
   │  ACK ack=1, rwnd ─────────────────────►│  cwnd=2
   │◄── DATA seq=1,2 ───────────────────────│
   │  ACK ack=3 ───────────────────────────►│  cwnd=4 …
   │        … refinamiento progresivo …      │
   │  VIEWPORT(nueva zona) ────────────────►│  descarta candidato staged, reprioriza
   │◄── DATA de la nueva zona ──────────────│
   │  (desaloja de caché algo lejano)        │
   │  FORGET(tile) + VIEWPORT(vuelve) ──────►│  vuelve a programar sus paquetes
```

### 8.3 Recuperación ante pérdida (Selective Repeat + SACK)

```
 S→C: DATA 10,11,12,13,14      (se pierde 11)
 C→S: ACK ack=11, SACK[12,15)  (recibí 10; 12,13,14 fuera de orden)
 C→S: ACK ack=11, SACK[12,15)  (dup)
 C→S: ACK ack=11, SACK[12,15)  (dup ×3 → IsLost: 3 SACK por encima de 11)
 S→C: DATA 11 (RETX)           (retransmite SOLO el faltante)
 C→S: ACK ack=15               (hueco cerrado; entrega ya estaba hecha fuera de orden)
```

---

## 9. Gestión de caché y navegador

- **Todo el intercambio de imagen ocurre por WebSocket** → se evitan cientos de
  *requests* HTTP por tiles (validable en las herramientas del navegador: apenas un
  request de upgrade + una conexión persistente).
- El cliente limita la caché a 160 tiles con LRU en modos 0/1. En modo 2 la limita a
  256 MiB y desaloja primero tiles con menor utilidad por byte (LRU desempata). Al
  desalojar un tile envía `FORGET`; si vuelve a ser necesario, el scheduler lo
  programa de nuevo. Esto es un nuevo envío de aplicación, distinto de una
  retransmisión de DATA por pérdida.
- Política de caché HTTP para los estáticos: `Cache-Control` en JS/CSS; los datos de
  imagen **no** se cachean por el navegador (van por el protocolo, no por `img`/HTTP).

---

## 10. Evidencia experimental

El transporte se validó con un **enlace simulado** (pérdida, reordenamiento y retardo)
y reloj virtual determinista (`TransportSelfTest`). Resultados (2000 segmentos, salvo
el severo con 1000):

| Escenario | Entrega | Retransmisiones | Timeouts | `cwnd` final | Observación |
|-----------|:-------:|:---------------:|:--------:|:-----------:|-------------|
| Sin pérdida, en orden | 2000/2000 | **0 (0 %)** | 0 | 89 (ssthresh 64) | Slow start→CA limpio; **cero** retransmisiones espurias |
| Reordenamiento fuerte (80 ms) | 2000/2000 | 294 (14.7 %) | 0 | 3 | 100 % entregado aun con reordenamiento patológico |
| Pérdida 10 % (datos y ACKs) | 2000/2000 | 399 (20 %) | 14 | 33 | recuperación por SACK + fast retransmit |
| Pérdida 30 % severa | 1000/1000 | 538 (54 %) | 193 | 9 | RTO domina (pocos paquetes para 3 dup-ACK); entrega total |

**Conclusiones:** entrega íntegra en todos los casos; el control de congestión reacciona
a la pérdida (baja `ssthresh`/`cwnd`) y crece en ausencia de ella; Selective Repeat no
retransmite de más cuando la red está ordenada.

Otras pruebas unitarias cubren DWT, bit-planes, `.h2k`, WebSocket, transporte y
scheduler. `RapidModesSchedulerTest`, `RapidSessionTest`, `test/client_modes_test.mjs`
y `test/rapid_integration_test.java` cubren políticas de modos, lotes, cambio de modo,
FORGET y progreso de dos sesiones. `run_vips_tests.sh` se ejecutó correctamente con
un ZIP Deep Zoom de muestra en los tres modos; la suite `./run_tests.sh` también
pasó con `VIPS_BIN` local. La integración ZIP reconstruyó los píxeles RGB exactos
de un fixture de 1200×800 en los tres modos y verificó lotes, retransmisión y
`FORGET`.

Para `Imagen-55GB-comprimida/055-843-000-80450114.png` (**136.325 × 136.325 px**;
**55.843.161.368 bytes**), libvips 8.15.1 generó `images/large_vips.zip` de
**74.534.432.660 bytes** en **280 s** con `VIPS_CONCURRENCY=8`, PNG compression 0
y tiles de 512 px. El ZIP contiene **95.301 entradas**. El lector Java abrió su
ZIP64 y una prueba WebSocket recibió
el mismo tile de borde de **53.366 bytes** en los modos 0/1/2, usando 70/34/33
frames DATA. Esta fue una prueba acotada de un tile; no reconstruyó la imagen
completa.

---

## 11. Tabla resumen de parámetros

| Parámetro | Valor | Referencia |
|-----------|-------|------------|
| Tile H2K | 512×512 | §3.1 |
| Niveles de resolución (DWT) | 5 | §3.1 |
| Tile ZIP predeterminado | 512×512, `overlap=0` | §3.4 |
| HELLO de imagen | v1 H2K; v2 ZIP | §5.5 |
| PNG bytes por fragmento `ZT` | ≤ 16.000 | §5.7 |
| Precinct | 64×64 | §3.1 |
| Umbral de ACK duplicados | 3 | §6.2 |
| `ssthresh` inicial | 64 segmentos | §6.3 |
| `cwnd` inicial | 1 segmento | §6.3 |
| RTO | `[200 ms, 60 s]` | §6.4 |
| Bloques SACK por ACK | ≤ 4 | §5.3 |
| Paquetes por DATA en lote | ≤ 16 | §5.2 |
| Payload máximo de lote | 32.768 bytes | §5.2 |
| Cola WebSocket por sesión | 1 MiB | §7 |
| Espacio de secuencia | u32 | §4 |

---

## 12. Referencias

1. **RFC 9293** — *Transmission Control Protocol (TCP)*. IETF, 2022. (ventana
   deslizante, ACK acumulativo, retransmisión, control de flujo).
2. **RFC 5681** — *TCP Congestion Control*. (slow start, congestion avoidance, fast
   retransmit/recovery).
3. **RFC 2018** — *TCP Selective Acknowledgment Options (SACK)*.
4. **RFC 6675** — *A Conservative Loss Recovery Algorithm Based on SACK* (regla
   *IsLost*).
5. **RFC 6298** — *Computing TCP's Retransmission Timer* (SRTT/RTTVAR/RTO).
6. Karn & Partridge — *Improving Round-Trip Time Estimates in Reliable Transport
   Protocols*, 1987.
7. **RFC 6455** — *The WebSocket Protocol*.
8. **RFC 1951** — *DEFLATE Compressed Data Format*.
9. **ISO/IEC 15444-1** — *JPEG 2000 image coding system* (niveles de resolución,
   precincts, quality layers, transformada 5/3 reversible).
10. A. R. Calderbank et al. — *Wavelet Transforms That Map Integers to Integers*, 1998.
11. D. Hilbert — *Über die stetige Abbildung einer Linie auf ein Flächenstück*, 1891
    (curva de Hilbert; orden de progresión).
12. Chiu & Jain — *Analysis of the Increase and Decrease Algorithms for Congestion
    Avoidance in Computer Networks*, 1989 (AIMD).
13. Cardwell et al. — *BBR: Congestion-Based Congestion Control*, 2016 (contexto de
    control por retardo, comparación).

---

*Documento vivo: la implementación integra scheduler, transporte, WebSocket, acceso a
`.h2k` y cliente de navegador. Las pruebas focalizadas listadas arriba cubren los
modos actuales; consulte el README para ejecutar la suite completa.*
