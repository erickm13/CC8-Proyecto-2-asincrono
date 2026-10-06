# Protocolo RAPID
### *Reliable Adaptive Progressive Image Delivery*
**Documento de especificación del protocolo — Proyecto 2 · Ciencias de la Computación VIII (Redes)**

Autor: **Erick Eleazar Mejía Moscoso**
Lenguaje del servidor: **Java 21** · Cliente: **HTML/CSS/JS** · Sin dependencias externas.

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
| **Sustrato de imagen** (`.h2k`) | *Qué información existe*: descompone la imagen en niveles de resolución, precincts y capas de calidad (base, no puntúa por sí sola). | §3 |
| **Transporte RAPID** | *Cómo se transmite de forma confiable y eficiente*: ventana deslizante, SEQ/ACK/**SACK**, **Selective Repeat**, **slow start** + control de congestión y de flujo. | §4–§8 |

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
- Al alejarse, el cliente **descarta** las capas finas que ya no necesita (disminución
  de resolución = *eliminación* de datos y liberación de memoria).
- La entrega es **confiable, controlada y priorizada**: nunca se deja al usuario
  desatendido en la zona que solicita a máxima definición.

Esto **no** es una galería ni un *zoom-in* de imágenes pre-generadas: existe
transferencia y eliminación real de información gobernada por el protocolo.

---

## 2. Arquitectura y pila de protocolos

```
        NAVEGADOR (cliente)                     SERVIDOR (Java, asíncrono)
  ┌─────────────────────────────┐        ┌───────────────────────────────┐
  │  UI: canvas, zoom/pan        │        │  Preprocesador  imagen → .h2k  │
  │  Decodificador (inverse DWT) │        │  H2kReader (acceso aleatorio)  │
  │  Receptor RAPID (ACK/SACK)   │        │  Scheduler (Hilbert+utilidad)  │
  │  Caché LRU de paquetes       │        │  Emisor RAPID (ventana, cc)    │
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

### 2.1 ¿Por qué implementar ARQ sobre WebSocket si TCP ya es confiable?

Decisión de diseño central y justificable:

- **WebSocket es un único flujo ordenado** → sufre *head-of-line blocking*: si un
  paquete de imagen tarda, bloquea a todos los que van detrás aunque ya estén listos.
- RAPID trocea la imagen en **paquetes independientes con número de secuencia** y,
  con **SACK + Selective Repeat**, el cliente confirma fuera de orden y el servidor
  **reprioriza al instante** cuando el usuario cambia de zona (TCP no puede: reordena
  y espera). Es el mismo principio con que **QUIC** evita el head-of-line sobre UDP;
  RAPID hace el análogo sobre WebSocket.
- **Control de flujo propio (`rwnd`)**: el cliente anuncia cuánta memoria/caché tiene,
  y el servidor nunca la excede → no se satura el navegador.
- **Retransmisión con sentido de aplicación**: cuando el cliente **desaloja** un
  paquete de su caché (LRU) y vuelve a necesitarlo, es una "pérdida" a nivel de
  aplicación que Selective Repeat recupera pidiéndolo de nuevo.

---

## 3. Sustrato de imagen: formato `.h2k` (la base)

> *Esta capa es la base necesaria para poder servir solo la zona/nivel visible.
> No constituye el aporte del protocolo, pero se documenta para completar el diseño.*

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

---

## 4. Transporte RAPID: modelo general

- **Dirección de datos:** servidor → cliente (mensajes **DATA**). El cliente confirma
  con **ACK** (acumulativo + SACK) y anuncia su ventana de recepción.
- **Unidad de secuencia:** el **segmento** (un DATA = un paquete de imagen
  autodescriptivo). A diferencia de TCP (secuencia por byte), RAPID numera por
  segmento, lo que simplifica SACK y Selective Repeat sin perder generalidad.
- **Espacio de secuencia:** entero de 32 bits, monótono creciente dentro de una
  sesión. Una sesión de 93 GB con paquetes de pocos KB usa ≈ 2·10⁷ segmentos, muy
  por debajo de 2³¹ (no hay *wrap-around*).
- **Entrega a la aplicación:** en **orden de llegada** (no estricto). Como cada
  payload es autodescriptivo, el cliente lo pinta apenas llega → evita el
  head-of-line blocking. El receptor deduplica y, en paralelo, mantiene el estado
  acumulativo/SACK para el control de retransmisión.

---

## 5. Formato de los mensajes (frames RAPID)

Todos los enteros son **big-endian**. El primer byte es el **tipo**. Los mensajes
viajan como *payload* de un frame binario de WebSocket.

### 5.1 Tabla de tipos

| Tipo | Valor | Dirección | Propósito |
|------|:-----:|-----------|-----------|
| `HELLO` | 1 | C → S | Abrir sesión: imagen solicitada, `rwnd` inicial, versión |
| `DATA` | 2 | S → C | Segmento de datos (paquete de imagen) |
| `ACK` | 3 | C → S | ACK acumulativo + SACK + `rwnd` + eco de timestamp |
| `VIEWPORT` | 4 | C → S | Cambio de zona/zoom visibles (repriorización) |
| `WIN` | 5 | C → S | Actualización de ventana de recepción (flow control) |
| `FORGET` | 6 | C → S | El cliente desalojó tiles de su caché (LRU) → reenviarlos si reaparecen |
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
| `flags` | u8 | bit0 = **RETX** (retransmisión; telemetría) |
| `payloadLen` | u16 | Longitud del payload |
| `payload` | bytes | **Paquete de imagen** (§5.6) |

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
 type=4 │ x(u32) │ y(u32) │ w(u32) │ h(u32) │ zoom(u8)
```
Coordenadas de la zona visible en el sistema de la imagen y nivel de zoom deseado.
Provoca **repriorización inmediata** en el scheduler (§7).

### 5.5 WIN / HELLO / FIN

- **WIN**: `type=5 │ rwnd(u32)` — actualización aislada de la ventana de recepción.
- **FORGET**: `type=6 │ count(u16) │ [tile(u32)]*` — el cliente informa qué tiles
  desalojó de su caché (LRU). El servidor los quita de su conjunto de
  ya-enviados, de modo que se **retransmiten** cuando el tile vuelva al viewport.
  Es la retransmisión con sentido de aplicación descrita en §2.1.
- **HELLO**: `type=1 │ imageId(u16) │ rwnd0(u32) │ version(u8)` — abre la sesión.
- **FIN**: `type=7` — cierre ordenado.

### 5.6 Payload de DATA: paquete de imagen (cabecera de aplicación)

```
 tile(u32) │ comp(u8) │ level(u8) │ py(u16) │ px(u16) │ layer(u8) │ dataLen(u16) │ deflate(bit-plane)…
```
El cliente usa `(tile, comp, level, py, px, layer)` para colocar el plano de bits en
el coeficiente correcto y reconstruir por inverse DWT.

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

El cliente **calcula `rwnd` dinámicamente** según su propio estado y lo anuncia en
cada ACK. El servidor jamás pone en vuelo más de `rwnd` segmentos
(`ventana = mín(cwnd, rwnd)`) → **protege al navegador** de saturación:

```
 headroom  = (MAX_TILES − tiles_en_cache) / MAX_TILES      (0..1)
 backlog   = min(decodificaciones_pendientes / 64, 1)       (0..1)
 rwnd      = MIN_RWND + headroom·(1 − backlog)·(MAX_RWND − MIN_RWND)
            (acotado a [MIN_RWND, MAX_RWND] = [8, 256] segmentos)
```

Cuando la caché está casi llena o hay mucho backlog de decodificación, `rwnd` se
encoge y el servidor reduce el ritmo; al desalojar (LRU) o vaciar el backlog, `rwnd`
sube y el flujo se recupera. Es un lazo de realimentación cerrado cliente↔servidor.
(Verificado: con `rwnd = 4` el emisor nunca supera 4 segmentos en vuelo.)

---

## 7. ¿Qué enviar? Scheduler (deadline + rate-distortion + Hilbert)

El control de congestión decide **cuántos** segmentos enviar; el **scheduler** decide
**cuáles** y en **qué orden**, actuando como fuente del emisor. De `VIEWPORT
(x,y,w,h,zoom)` se derivan los paquetes candidatos (tiles visibles + un anillo de
prefetch, niveles hasta el zoom pedido, precincts, capas pendientes aún no enviadas)
y se **ordenan** por una clave de 5 criterios (de más a menos prioritario):

| # | Criterio | Efecto |
|---|----------|--------|
| 1 | **deadline** (0 visible / 1 prefetch) | lo que se ve ahora va antes que la precarga de alrededores |
| 2 | **resolución** (nivel ascendente) | la imagen aparece completa y borrosa y se va afinando |
| 3 | **capa de calidad** (plano MSB→LSB) | progresión por calidad; garantiza planos **contiguos** por precinct (requisito del decodificador) |
| 4 | **utilidad/byte** (descendente) | **rate-distortion**: entre precincts del mismo plano, primero el que más detalle aporta por byte |
| 5 | **Hilbert** (tile, precinct) | desempate espacial: cobertura homogénea, regiones vecinas juntas (Hilbert, 1891) |

**Utilidad/byte (criterio 4).** Para el plano de bits `p` de un precinct con `N`
coeficientes y `B` bytes comprimidos:
```
utilidad(paquete) = N · 2^(2p) / B
```
El factor `2^(2p)` aproxima la reducción de **error cuadrático** al añadir ese plano
(cada coeficiente reduce su incertidumbre a la mitad por plano); dividir entre `B`
da la ganancia **por byte transmitido** (principio rate-distortion, análogo al PCRD
de JPEG2000). Así, dentro de cada plano, los precincts más informativos (bordes,
texto) se envían antes que las zonas planas.

Al cambiar el viewport, la cola se **reconstruye** al instante para la nueva zona
(los paquetes ya enviados se excluyen); los que dejan de ser visibles simplemente no
se re-encolan. La numeración por segmento independiente permite esta repriorización
sin bloqueos.

---

## 8. Máquina de estados y flujos

### 8.1 Estados de la sesión

```
   CLOSED ──HELLO──► OPEN ──(streaming DATA/ACK, VIEWPORT/WIN)──► OPEN
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
   │  HELLO(img, rwnd0) ───────────────────►│
   │  VIEWPORT(x,y,w,h,zoom) ──────────────►│  scheduler prioriza
   │◄── DATA seq=0 (R0 completo, borroso) ──│  cwnd=1 (slow start)
   │  ACK ack=1, rwnd ─────────────────────►│  cwnd=2
   │◄── DATA seq=1,2 ───────────────────────│
   │  ACK ack=3 ───────────────────────────►│  cwnd=4 …
   │        … refinamiento progresivo …      │
   │  VIEWPORT(nueva zona) ────────────────►│  cancela obsoletos, reprioriza
   │◄── DATA de la nueva zona ──────────────│
   │  (desaloja de caché algo lejano)        │
   │  VIEWPORT(vuelve) → re-solicita ──────►│  retransmite lo desalojado
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
- El cliente mantiene una **caché LRU** de tiles decodificados (límite configurable,
  p.ej. 160 tiles). Al salir del viewport, los tiles menos usados se **desalojan**
  (eliminación de información, se libera memoria) y se envía un `FORGET` con sus
  índices. Si el usuario vuelve, el siguiente `VIEWPORT` hace que el servidor los
  **retransmita** (verificado: tras `FORGET(0,1)` se reenvían exactamente los
  paquetes de esos tiles).
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

Otras pruebas unitarias: DWT reversible exacta; bit-planes con error monótono → 0 y
reconstrucción sin pérdida del `.h2k`; handshake WebSocket coincidente con el ejemplo
del RFC 6455.

---

## 11. Tabla resumen de parámetros

| Parámetro | Valor | Referencia |
|-----------|-------|------------|
| Tile | 512×512 | §3.1 |
| Niveles de resolución (DWT) | 5 | §3.1 |
| Precinct | 64×64 | §3.1 |
| Umbral de ACK duplicados | 3 | §6.2 |
| `ssthresh` inicial | 64 segmentos | §6.3 |
| `cwnd` inicial | 1 segmento | §6.3 |
| RTO | `[200 ms, 60 s]` | §6.4 |
| Bloques SACK por ACK | ≤ 4 | §5.3 |
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

*Documento vivo: refleja el diseño e implementación de RAPID a la fecha. La capa de
transporte está implementada y probada; la integración end-to-end (scheduler ↔
WebSocket ↔ H2kReader) y el cliente de navegador están en desarrollo siguiendo esta
especificación.*
