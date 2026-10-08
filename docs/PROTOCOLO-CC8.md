# Protocolo RAPID
### *Reliable Adaptive Progressive Image Delivery*
**Documento de especificación del protocolo — Proyecto 2 · Ciencias de la Computación VIII (Redes)**

Autor: **Erick Eleazar Mejía Moscoso** · Lenguaje del servidor: **Java 21** (solo JDK, sin
dependencias externas en ejecución) · Cliente: **HTML/CSS/JS**.

> El código al que se refiere cada sección está en `src/main/java/com/cc8/server/` (servidor)
> y `public/js/` (cliente). Las pruebas que sustentan cada afirmación están en las clases
> `*Test` y en `test/*.mjs`; se ejecutan todas con `./run_tests.sh`.

---

## 1. Objetivo y planteamiento del problema

Una imagen de 17–93 GB no puede transferirse completa a un navegador: saturaría el ancho
de banda, la memoria y enviaría datos que el usuario nunca verá. **RAPID** es un protocolo
de nivel de aplicación, con mecanismos de transporte adaptados de TCP, que entrega **solo la
información necesaria para lo que el usuario está mirando** y la **refina progresivamente**
hasta la máxima definición requerida (en las imágenes de evaluación, los números deben
quedar legibles al acercarse).

Esto se logra con **transferencia y eliminación selectiva de información**:

- Primero se muestra una **miniatura completa** (overview) de toda la imagen.
- Al acercarse, solo la **zona visible** se refina a máxima resolución.
- Al alejarse o moverse, el cliente **descarta** lo que ya no necesita (libera memoria) y, si
  se vuelve a necesitar, el servidor lo **retransmite**.

No es una galería ni un *zoom-in* de imágenes pre-generadas: existe transferencia y eliminación
reales de información gobernadas por el protocolo y sus políticas de planificación.

---

## 2. Arquitectura y pila de protocolos

```
        NAVEGADOR (cliente)                        SERVIDOR (Java, asíncrono)
  ┌──────────────────────────────┐          ┌──────────────────────────────────┐
  │ app.js / zip_viewer.js        │          │ Preprocesado offline:            │
  │  - visor canvas, zoom/pan     │          │   · Preprocessor  PNG → .h2k      │
  │  - predicción de movimiento   │          │   · preprocess_vips.sh PNG → .zip │
  │ decode.js  (inverse DWT)      │          │ Servicio:                        │
  │ receiver.js (ACK/SACK, batch) │          │   · H2kReader / ZipPyramidReader  │
  │ wire.js   (frames binarios)   │          │   · RapidScheduler (3 modos)      │
  │ caché LRU + FORGET + rwnd      │◄────────►│   · ReliableSender (ARQ)          │
  └──────────────┬───────────────┘  RAPID    │   · ImageProtocolHandler (DRR)    │
                 │  Frames binarios (WebSocket, RFC 6455)                        │
                 │  HTTP/1.1 (archivos iniciales + /api/manifest + /api/overview)│
                 └───────────────────────────────────────────────────────────────┘
                            TCP / IP  (provisto por el sistema operativo)
```

### 2.1 Dos backends de imagen

RAPID sirve dos representaciones distintas de la imagen con **el mismo transporte y las
mismas políticas de planificación**. El cliente elige una u otra en el saludo inicial (§7.6):

| Backend | Qué es | Preprocesador | Lector | Progresividad |
|---------|--------|---------------|--------|---------------|
| **A · `.h2k`** | Descomposición wavelet propia (precincts + capas de calidad por planos de bits). | `Preprocessor` (Java) | `H2kReader` | Por **resolución y por calidad** (bit-planes). |
| **B · ZIP Deep Zoom** | Pirámide de tiles PNG (DeepZoom/DZI) empaquetada en un ZIP. | `preprocess_vips.sh` (libvips `dzsave`) | `ZipPyramidReader` | Por **resolución** (cada tile es un PNG completo). |

El backend A es el aporte propio principal (wavelet + capas + rate-distortion). El backend B
demuestra que el mismo transporte RAPID sirve también una pirámide estándar, útil cuando se
dispone de libvips para preprocesar imágenes de decenas de GB en segundos.

### 2.2 ¿Por qué implementar ARQ sobre WebSocket si TCP ya es confiable?

Decisión de diseño central:

- **WebSocket es un único flujo ordenado** → sufre *head-of-line blocking*: un paquete lento
  bloquea a todos los que van detrás aunque estén listos.
- RAPID trocea la imagen en **paquetes independientes con número de secuencia**; con
  **SACK + Selective Repeat** el cliente confirma fuera de orden y el servidor **reprioriza al
  instante** al cambiar de zona (TCP no puede). Es el principio con que **QUIC** evita el
  head-of-line sobre UDP; RAPID hace el análogo sobre WebSocket.
- **Control de flujo propio (`rwnd`)** y **control de congestión** permiten que el servidor
  module el ritmo según la memoria/caché del cliente y según cuántos clientes atiende.
- **Retransmisión con sentido de aplicación**: cuando el cliente desaloja de su caché (LRU) y
  vuelve a necesitar una zona, es una "pérdida" de aplicación que el protocolo recupera.

---

## 3. Backend A — formato `.h2k` (wavelet)

### 3.1 Descomposición

Cada imagen se preprocesa **una sola vez, por franjas (strips) de filas**, sin cargarla
completa en RAM (apto para PNG de decenas de GB; §3.4):

1. **Tiles**: la imagen se parte en bloques de `512×512` (potencia de dos).
2. **DWT Haar reversible** (transformada S entera, sin pérdida) con `5` niveles → produce
   **niveles de resolución** `R0` (LL, la más gruesa, `16×16`) … `R5` (detalle más fino).
   ```
   Forward (por par a0,a1):  d = a1 − a0 ;  s = a0 + ⌊d/2⌋
   Inverse (por par s,d):    a0 = s − ⌊d/2⌋ ;  a1 = a0 + d
   ```
   Al ser entera y reversible, la reconstrucción con todos los planos es **exacta** (prueba
   `DwtSelfTest`). Antes de transformar, cada muestra de 8 bits se centra restando 128
   (`LEVEL_SHIFT`).
3. **Precincts**: cada nivel de resolución se subdivide en regiones de `64×64` → unidad de
   **selección espacial**.
4. **Capas de calidad (bit-planes)**: los coeficientes de cada precinct se codifican por
   **planos de bits**, del más significativo (MSB) al menos (LSB), con **signo perezoso** (el
   bit de signo se emite cuando el coeficiente se vuelve significativo por primera vez, como
   en JPEG2000). Cada plano es una *quality layer*: recibir más planos = más nitidez. Cada
   plano se comprime con **DEFLATE** (RFC 1951, `java.util.zip`); en el navegador se
   descomprime con `DecompressionStream('deflate-raw')`.

La unidad mínima direccionable es el **paquete** `(tile, componente, nivel, precinct, capa)`.
Prueba `BitPlaneSelfTest`: el error de reconstrucción decrece monótonamente a 0 al añadir
planos.

### 3.2 Layout del archivo `.h2k`

```
┌────────────┬──────────────────────────────┬──────────────────────┬────────────┬──────────┐
│ Header 64B │ Paquetes (planos DEFLATE)     │ Índices por tile     │ Directorio │ Overview │
│            │  intercalados con sus índices │ (interc. con datos)  │ de tiles   │ (PNG)    │
└────────────┴──────────────────────────────┴──────────────────────┴────────────┴──────────┘
```

**Header (64 bytes, big-endian):** `magic "H2K1"`, versión, colorTransform, componentes (1
gris/3 RGB), bitDepth, tileSize, levels, precinct, width, height, tilesX, tilesY,
`tileDirOffset` (long), y los campos de overview (`overviewOffset/Len/W/H`) en el espacio
reservado. El **directorio de tiles** lista `(offset, longitud)` del índice de cada tile, para
que el servidor lea **solo** el índice del tile que necesita (nunca el índice completo de una
imagen de 50 GB+). El servicio usa lectura posicional (`FileChannel.read(buf, pos)`).

### 3.3 Overview (miniatura)

El preprocesador ensambla el bloque LL (`s0×s0`, con `s0 = tileSize/2^levels`) de todos los
tiles en una miniatura de toda la imagen a resolución `1/2^levels` (p.ej. 1/32), la guarda
como PNG al final del `.h2k` y la expone en **`GET /api/overview`**. El cliente la pinta como
capa base **al instante** cuando la vista está alejada y solo pide tiles al acercarse. Así,
explorar una imagen de 93 GB nunca enumera sus decenas de miles de tiles.

### 3.4 Preprocesamiento por streaming y multihilo

`PngStreamReader` decodifica el PNG de origen **fila por fila** (parsea los chunks IDAT, los
descomprime con `Inflater` y aplica los 5 filtros PNG: None/Sub/Up/Average/Paeth), manteniendo
en memoria solo dos escanlines. `H2kWriter` procesa una **franja de `tileSize` filas** a la
vez y transforma/codifica sus tiles en paralelo (`--threads=N`), con nivel DEFLATE
configurable (`--compression-level=0..9`). Verificación `PngIngestTest`: el `.h2k` por
streaming es **byte-idéntico** al producido por ImageIO en memoria, y la reconstrucción es sin
pérdida. Medido: imagen real de **75471×75471 (17 GB)** → `.h2k` de 15.4 GB en ~33 min con RAM
acotada.

---

## 4. Backend B — pirámide Deep Zoom en ZIP (libvips)

### 4.1 Generación

`preprocess_vips.sh` llama a `vips dzsave` para producir una pirámide DeepZoom **sin solape**
(`--overlap 0`), con **PNG sin pérdida** (`compression=0`) y **`--depth onetile`** (el nivel 0
es un único tile = overview), empaquetada como ZIP sin comprimir (`--container zip
--compression 0`). Es muy rápido (segundos para varios GB) porque usa la rutina optimizada de
libvips; es la vía práctica para preparar imágenes gigantes cuando libvips está disponible.

### 4.2 Lectura (`ZipPyramidReader`)

Abre el ZIP, valida el descriptor `.dzi` (Format=png, Overlap=0, dimensiones), reconstruye los
niveles `0..maxLevel` (cada nivel duplica la resolución; nivel 0 = un tile) e indexa cada tile
`(level, tx, ty)` con un **id plano** `offsets[level] + ty·tilesX + tx`. Lee tiles **bajo
demanda** desde el ZIP con una **caché LRU de 64 MB**, valida la firma PNG y que las
dimensiones del IHDR coincidan con las esperadas para ese tile (defensa contra ZIP
manipulados).

### 4.3 Fragmentación en paquetes (`ZipTilePacket`)

Como un tile PNG puede exceder el tamaño de un segmento, se **fragmenta** en partes de
`FRAGMENT = 16 000` bytes. Cada fragmento es un paquete autodescriptivo (cabecera 22 B):
```
'Z' 'T' ver(1) | id(4) | level(1) | tx(2) ty(2) | part(2) parts(2) | totalLen(4) | len(2) | datos
```
El cliente reensambla las `parts` de cada tile y decodifica el PNG completo. La clave de
"enviado" es `(id<<16)|part`, de modo que `FORGET(id)` olvida todos los fragmentos de ese tile.

---

## 5. Modelo del transporte RAPID

- **Dirección de datos:** servidor → cliente (mensajes **DATA**). El cliente confirma con
  **ACK** acumulativo + bloques SACK y anuncia su ventana `rwnd`.
- **Unidad de secuencia:** el **segmento** (un DATA). En los modos 1/2 un segmento puede
  transportar un **lote** de hasta 16 paquetes de imagen (§8.6); en el modo 0, un paquete por
  segmento. SEQ es un entero de 32 bits monótono por sesión (una sesión de 93 GB usa ≈2·10⁷
  segmentos, muy por debajo de 2³¹: no hay *wrap-around*).
- **Entrega a la aplicación:** en **orden de llegada** (no estricto). Cada paquete es
  autodescriptivo, así que el cliente lo pinta apenas llega → sin head-of-line blocking. El
  receptor deduplica por SEQ y mantiene el estado acumulativo/SACK para el control de
  retransmisión.

---

## 6. Negociación de sesión (HELLO)

La comunicación inicial (HTML/CSS/JS, `/api/manifest`, `/api/overview`) va por **HTTP**. Al
abrir el WebSocket, la primera trama es un **HELLO** que negocia el backend y el modo:

```
HELLO:  type(1)=1 | version(1) | mode(1)
        version = 1 → backend .h2k ;  2 → backend ZIP DeepZoom
        mode    = 0 / 1 / 2  (política de planificación, §9)
```

El servidor valida que `version` coincida con el backend que sirve y que `mode ∈ {0,1,2}`.
Hasta que no hay HELLO válido, en el backend ZIP se ignoran ACK/VIEWPORT/FORGET (evita operar
una sesión sin negociar). Al cambiar de modo, el emisor **descarta lo preparado-no-enviado**
(y lo devuelve al scheduler, §8.1) y activa/desactiva el *batching* (batching solo en modos
1/2).

---

## 7. Formato de los mensajes (wire)

Enteros **big-endian**; el primer byte es el **tipo**. Los mensajes viajan como *payload* de
un frame binario de WebSocket (el navegador hace el enmarcado/máscara de WebSocket; el
servidor implementa WebSocket a mano, §11).

### 7.1 Tabla de tipos

| Tipo | Valor | Dirección | Propósito |
|------|:-----:|-----------|-----------|
| `HELLO` | 1 | C → S | Negocia backend (version) y modo |
| `DATA` | 2 | S → C | Segmento de datos (uno o un lote de paquetes) |
| `ACK` | 3 | C → S | ACK acumulativo + SACK + `rwnd` + eco de timestamp |
| `VIEWPORT` | 4 | C → S | Zona/zoom visibles (+ predicción opcional) |
| `FORGET` | 6 | C → S | Tiles desalojados de la caché (LRU) → reenviar si reaparecen |
| `FIN` | 7 | ambos | Cierre de sesión |

(`WIN`=5 está reservado; el `rwnd` se transporta dentro de cada ACK.)

### 7.2 DATA (servidor → cliente)

```
 type(1)=2 | seq(4) | sendTs(8) | flags(1) | payloadLen(2) | payload
```
`flags`: bit0 = **RETX** (retransmisión), bit1 = **BATCH**. `sendTs` es el timestamp de envío,
reflejado por el cliente en el ACK para medir RTT. Si **BATCH** está activo, `payload` es un
lote `[len(2) paquete]…` (≤16 paquetes, ≤32 768 B); si no, es un único paquete de imagen.

### 7.3 ACK (cliente → servidor)

```
 type(1)=3 | ack(4) | rwnd(4) | echoTs(8) | nSack(1) | [start(4) end(4)]*
```
`ack` = siguiente SEQ esperado (acumulativo); `rwnd` = ventana de recepción en **segmentos**
(flow control); `echoTs` = `sendTs` del segmento que disparó el ACK (RTT); bloques SACK =
rangos `[start,end)` recibidos fuera de orden (RFC 2018). El servidor **valida** el ACK
(longitud exacta, `ack ∈ [sndUna, sndNxt]`, bloques SACK dentro de rango) antes de usarlo.

### 7.4 VIEWPORT (cliente → servidor)

```
 Básico (18 B): type(1)=4 | x(4) y(4) w(4) h(4) | zoom(1)
 Con predicción (21 B):  … | dx(int8) dy(int8) confidence(uint8)
```
`(x,y,w,h)` es la zona visible en coordenadas de la imagen completa; `zoom` es el nivel de
resolución máximo solicitado. En el modo Predictivo el cliente añade un **vector de
movimiento** `(dx,dy)` normalizado a `[-1,1]` (como `int8/127`) y una **confianza** `[0,1]`
(`uint8/255`). Provoca repriorización inmediata del scheduler.

### 7.5 FORGET / FIN

- **FORGET**: `type(1)=6 | count(2) | [tile(4)]*` — el cliente informa qué tiles desalojó; el
  servidor los quita de su conjunto de ya-enviados para retransmitirlos si reaparecen.
- **FIN**: `type(1)=7` — cierre ordenado (eco).

### 7.6 Payload: paquete de imagen (`ImagePacket`) y fragmento ZIP (`ZipTilePacket`)

Backend A — **ImagePacket** (cabecera 18 B, autodescriptiva: el cliente coloca el plano y
reconstruye sin necesitar el índice del archivo):
```
 tile(4) comp(1) level(1) py(2) px(2) layer(1) numPlanes(1) numCoeffs(4) dataLen(2) deflate(bit-plane)
```
Backend B — **ZipTilePacket** (cabecera 22 B, §4.3): fragmento de un PNG de tile.

---

## 8. Mecanismos de confiabilidad y control (transporte)

Implementados en `ReliableSender` (servidor) y `ReliableReceiver`/`receiver.js` (referencia
Java + port JS del cliente). Referencias: **RFC 9293** (TCP), **RFC 5681** (congestión),
**RFC 2018/6675** (SACK), **RFC 6298** (RTO).

### 8.1 Ventana deslizante y *staging*

En vuelo como máximo **`min(cwnd, rwnd)`** segmentos. `sndUna` = menor SEQ sin confirmar;
`sndNxt` = siguiente a asignar; `enVuelo = (sndNxt − sndUna) − |SACKed|`. El emisor **prepara**
el siguiente payload (lo *stagea*) antes de transmitir; si el canal WebSocket está saturado
(§8.5) o cambia el viewport, ese payload *staged* se **devuelve al scheduler**
(`requeueUnsent`) para que lo reprioritice — nada se pierde ni se envía fuera de prioridad.

### 8.2 ACK acumulativo + SACK y Selective Repeat

El receptor entrega cada paquete al llegar (dedup por SEQ), mantiene `rcvNxt` y el conjunto de
SEQ fuera de orden, y responde ACK acumulativo + bloques SACK. El emisor, con `ack`+SACK, sabe
**exactamente qué falta** y retransmite **solo eso** (Selective Repeat, no Go-Back-N).
**Regla *IsLost* (RFC 6675):** un hueco se considera perdido (no mero reordenamiento) cuando
hay **≥3 segmentos con SACK por encima**; se limita a 4 retransmisiones guiadas por SACK por
ACK y se evita reenviar el mismo hueco más de una vez por RTT. Prueba `TransportSelfTest`:
entrega íntegra con pérdida 10–30 % y reordenamiento; `SchedulerOrderTest` verifica que cada
precinct recibe sus planos en orden contiguo (requisito del decodificador).

### 8.3 Control de congestión (estilo Reno)

| Fase | Condición | Regla de `cwnd` |
|------|-----------|-----------------|
| **Slow start** | `cwnd < ssthresh` | `cwnd += 1` por ACK (×2/RTT) |
| **Congestion avoidance** | `cwnd ≥ ssthresh` | `cwnd += 1/cwnd` por ACK (+1/RTT) |
| **Fast retransmit/recovery** | 3 ACK duplicados | `ssthresh = máx(enVuelo/2, 2)`, `cwnd = ssthresh+3`, retransmite el hueco |
| **Timeout (RTO)** | vence el temporizador | `ssthresh = máx(enVuelo/2, 2)`, `cwnd = 1`, backoff `RTO×2`, retransmite `sndUna` |

Iniciales: `cwnd = 1`, `ssthresh = 64` segmentos.

### 8.4 Estimación de RTT y RTO (RFC 6298 + Karn)

```
 SRTT = 7/8·SRTT + 1/8·muestra ;  RTTVAR = 3/4·RTTVAR + 1/4·|SRTT−muestra|
 RTO  = clamp( SRTT + 4·RTTVAR , 200 ms , 60 s )
```
**Karn:** no se muestrea RTT de segmentos retransmitidos; sí de los confirmados por SACK que
se enviaron una sola vez (RTT válido aun bajo pérdida). Cada timeout duplica el RTO (backoff).

### 8.5 Control de flujo (`rwnd` dinámico) y *backpressure*

El cliente **calcula `rwnd` dinámicamente** y lo anuncia en cada ACK. El servidor nunca pone
en vuelo más de `rwnd` segmentos:
```
 headroom = (capacidad_caché − usado) / capacidad_caché         (0..1)
 backlog  = min(decodificaciones_pendientes / 64, 1)            (0..1)
 rwnd     = MIN + headroom·(1 − backlog)·(MAX − MIN)
```
Rangos: modo 0 `[8,256]` segmentos; modos 1/2 `[1,16]` segmentos (cada segmento es un lote de
hasta 16 paquetes). Caché llena o mucho backlog → `rwnd` baja → el servidor se frena; al
desalojar (LRU) o vaciar backlog → sube. Prueba `FlowControlTest`: con `rwnd=4` el emisor nunca
supera 4 segmentos en vuelo y entrega todo.

Además, la **cola de escritura de WebSocket está acotada** (`MAX_QUEUED_BYTES = 1 MiB`):
`trySend` devuelve *false* si la cola está llena; el emisor conserva el payload *staged* y lo
reintenta. Es una segunda barrera (backpressure) que impide acumular datos sin límite si el
socket del cliente va lento.

### 8.6 *Batching* de paquetes

En los modos 1/2 el emisor agrupa hasta **16 paquetes** de imagen (≤32 768 B) en un solo
segmento DATA (flag BATCH), con enmarcado `[len(2) paquete]…`. Reduce el número de segmentos,
ACKs y la sobrecarga por segmento cuando la zona visible genera muchos paquetes pequeños. El
cliente (`parseDataPackets`) valida y separa el lote. El modo 0 no usa batching (un paquete por
segmento) para mantener su comportamiento legacy.

### 8.7 Equidad multicliente: Deficit Round Robin entre sesiones

`ImageProtocolHandler` corre un **temporizador** cada 20 ms y un **despachador DRR** que, en
cada ronda, da a cada sesión con trabajo pendiente el **mismo presupuesto de bytes**
(`FAIR_QUANTUM ≈ 32 KiB`): cada sesión acumula un *deficit*, envía segmentos mientras quepan en
su presupuesto, y el sobrante se arrastra a la siguiente ronda (Deficit Round Robin, Shreedhar
& Varghese). Así varios clientes comparten el servidor de forma **justa** y **acotada** (ningún
cliente monopoliza el ancho de banda ni la CPU). Una sesión con error queda aislada y no
detiene al resto.

### 8.8 Estimación de carga y *load shedding*

La carga del servidor se estima como `load = min(1, nº_sesiones / 16)` y se comunica a cada
scheduler. Bajo carga, los modos 1/2 **recortan trabajo**: con `load ≥ 0.70` no se hace
prefetch; con `load ≥ 0.85` las zonas visibles solo reciben el plano más significativo (se
omite el refinamiento fino). El modo 0 no se ve afectado.

### 8.9 `SharedPacketStore` (coalescencia de lecturas)

Si varios clientes piden el **mismo** paquete del `.h2k` al mismo tiempo, `SharedPacketStore`
coalesce la lectura de disco: solo una lee y las demás esperan ese resultado. Reduce E/S
redundante con muchos clientes sobre la misma imagen.

---

## 9. Políticas de planificación: los tres modos RAPID

El control de congestión decide **cuántos** segmentos enviar; el **scheduler** decide
**cuáles** y en **qué orden**, actuando como fuente (`SegmentSource`) del emisor. Es la pieza
que el usuario selecciona en la interfaz ("Modo RAPID") y que se negocia en el HELLO. Hay una
implementación para cada backend (`Scheduler` para `.h2k`, `ZipPyramidScheduler` para ZIP) que
comparten el mismo marco y las mismas tres políticas.

### 9.0 Marco común

De `VIEWPORT` se derivan los paquetes candidatos (tiles visibles + un anillo de prefetch,
niveles hasta el zoom pedido, capas pendientes aún no enviadas). Conceptos compartidos:

- **deadline**: 0 = visible ahora; 1 = prefetch (precarga de alrededores).
- **utilidad/byte** (rate-distortion): para el plano `p` de un precinct con `N` coeficientes y
  `B` bytes comprimidos, `utilidad = N·2^(2p)/B`. El factor `2^(2p)` aproxima la reducción de
  error cuadrático al añadir ese plano; dividir entre `B` da la ganancia por byte. (En el
  backend ZIP, `utilidad = píxeles_del_tile / bytes`.)
- **Hilbert**: orden de recorrido espacial (curva de Hilbert) que preserva localidad; se usa
  como desempate para cobertura homogénea.
- **token bucket**: el prefetch se limita con un cubo de fichas (≈65 536 bytes/s) para que la
  precarga no compita con lo visible.
- **forget / requeue**: al desalojar (FORGET) o cambiar de viewport, los paquetes afectados
  salen del conjunto "enviado" y vuelven a la cola de candidatos.

La enumeración está **acotada** (máx. 256 tiles por viewport; la vista alejada se cubre con el
overview), protegiendo al servidor en imágenes gigantes.

### 9.1 Modo 0 — Progresivo (Hilbert)  ·  *RLCP + rate-distortion*

Orden base por cinco criterios (de más a menos prioritario):

1. **deadline** (visible antes que prefetch).
2. **resolución** (nivel ascendente): la imagen aparece completa y borrosa y se afina.
3. **capa de calidad** (plano MSB→LSB): progresión por calidad; garantiza planos **contiguos**
   por precinct (requisito del decodificador).
4. **utilidad/byte** descendente entre precincts del mismo plano (rate-distortion): primero los
   precincts más informativos (bordes, texto).
5. **Hilbert** (desempate espacial).

Es la política más predecible; sirve de línea base. Pruebas: `SchedulerOrderTest`,
`SchedulerForgetTest`.

### 9.2 Modo 1 — Cobertura EDF  ·  *Earliest-Deadline-First por fases*

Prioriza **cubrir** cuanto antes toda la zona visible con lo más importante, respetando
**deadlines**. Ordena por **fase**:

- **Fase 0** — visible, `nivel 0, capa 0` (la base gruesa de lo visible: lo más urgente).
- **Fase 1** — visible, `capa 0` (el plano MSB de **todos** los precincts visibles: cobertura).
- **Fase 2** — visible, resto de planos (refinamiento).
- **Fase 3** — prefetch (anillo), limitado por el token bucket.

Dentro de la fase, ordena por nivel y capa, luego utilidad/byte y Hilbert. Se asignan
**plazos**: la base gruesa tiene *deadline* de **250 ms** y el resto de la base **1000 ms**; el
scheduler **cuenta los deadlines incumplidos** (`deadlineMisses`) como métrica de calidad de
servicio. Bajo carga aplica el *load shedding* de §8.8. Inspirado en **EDF** (Liu & Layland).

### 9.3 Modo 2 — Predictivo DRR  ·  *prefetch direccional + refinamiento por rondas*

Dos ideas combinadas:

1. **Prefetch predictivo**: a partir del vector de movimiento `(dx,dy)` y la `confianza` que
   envía el cliente (§7.4), precarga una **franja de tiles en la dirección del paneo**, de
   anchura proporcional a la confianza (`predictiveStrip`). Si el usuario se mueve de forma
   consistente, el detalle de "hacia dónde va" ya está en camino antes de que llegue.
2. **Refinamiento por rondas (Deficit Round Robin)**: la cola inicial toma **una sola capa por
   precinct** (el primer plano pendiente); tras enviar esa capa, el scheduler **inserta la
   capa siguiente** de ese precinct en su posición de prioridad. Así todos los precincts
   visibles reciben una pasada antes de que cualquiera reciba su segunda — refinamiento
   equitativo y "redondo".

El cliente estima el movimiento con `predictPan`: acumula los desplazamientos de paneo de los
últimos **1.5 s** (hasta 8 muestras) y calcula la confianza como
`255·e^(−edad/1200)·min(1, n/3)` (decae con el tiempo sin paneo y crece con la consistencia).

### 9.4 Equivalente en el backend ZIP

`ZipPyramidScheduler` aplica las **mismas tres políticas** sobre tiles PNG: ordena por
visible/nivel/Hilbert (o utilidad en modo 2), fragmenta cada tile (§4.3), limita el prefetch
con token bucket, aplica el mismo prefetch predictivo y el mismo *load shedding* (bajo carga
conserva solo el overview y el nivel exacto pedido, omitiendo niveles intermedios).

---

## 10. Cliente (navegador)

- **Decodificación.** Backend A: `decode.js` descomprime cada plano (`DecompressionStream`),
  reconstruye los coeficientes (planos de bits + signo), hace la **DWT inversa** y pinta el
  tile. Es el port fiel de `ClientReconstructor.java`, **verificado en Node** (`js_verify.mjs`)
  contra la referencia Java: reconstrucción idéntica (0 diferencias). Backend B: reensambla las
  partes del PNG y lo dibuja directo.
- **Predicción de movimiento** (`predictPan`, §9.3), solo en modo 2.
- **Caché y memoria.** Caché LRU por tiles (modos 0/1, `MAX_TILES=160`) o por bytes (modo 2,
  `MAX_CACHE_BYTES=256 MiB`). Al desalojar envía `FORGET` → el servidor retransmite si la zona
  reaparece (`forget_verify.mjs`: tras `FORGET(0,1)` se reenvían exactamente los paquetes de
  esos tiles). El `rwnd` dinámico (§8.5) se recalcula en cada cuadro.
- **Overview y *gating* por zoom.** Al cargar pinta el overview; solo pide tiles por `VIEWPORT`
  cuando la vista abarca ≤120 tiles (si está más alejado, basta el overview). Reduce drásticamente
  los requests (validable en DevTools: una sola conexión WebSocket, no cientos de requests HTTP).
- **Diagnóstico de latencia.** La UI muestra medianas de 7 tiempos (VIEWPORT→primer paquete,
  recepción→inflate, inflate→reconstrucción, duración de `draw()`, etc.) para analizar el
  comportamiento del protocolo.

---

## 11. Máquina de estados y flujos

```
   (HTTP: GET /, /api/manifest, /api/overview)
        │
        ▼  Upgrade: websocket (handshake RFC 6455)
   CLOSED ──HELLO(version,mode)──► OPEN ──(VIEWPORT/ACK/FORGET ↔ DATA)──► OPEN ──FIN──► CLOSED
```

**Recuperación ante pérdida (Selective Repeat + SACK):**
```
 S→C: DATA 10,11,12,13,14      (se pierde 11)
 C→S: ACK ack=11, SACK[12,15)  ×3  (IsLost: ≥3 SACK por encima de 11)
 S→C: DATA 11 (RETX)           (retransmite SOLO el faltante)
 C→S: ACK ack=15               (hueco cerrado)
```

El servidor HTTP/WebSocket es **asíncrono** (`AsynchronousServerSocketChannel`, NIO.2),
multicliente, con pool de hilos acotado; el handshake WebSocket (cálculo de
`Sec-WebSocket-Accept` con SHA-1+Base64) y el enmarcado se implementan a mano (`WebSocketSelfTest`
coincide con el ejemplo del RFC 6455).

---

## 12. Evidencia experimental (pruebas)

Todas se ejecutan con `./run_tests.sh` (sin internet). Resumen:

| Prueba | Qué valida |
|--------|------------|
| `DwtSelfTest` | DWT Haar reversible exacta |
| `BitPlaneSelfTest` | capas de calidad: error → 0 |
| `RoundTripTest` / `PngIngestTest` | `.h2k` sin pérdida; ingesta PNG streaming == ImageIO + overview |
| `HilbertSelfTest` | curva de Hilbert (biyección + localidad) |
| `WebSocketSelfTest` | handshake (ejemplo RFC 6455) + enmarcado |
| `TransportSelfTest` | entrega 100 % bajo pérdida 10–30 % (SACK, congestión, RTO) |
| `FlowControlTest` | el emisor nunca excede `rwnd` |
| `IntegrationSelfTest` | scheduler→transporte→reconstrucción sin pérdida sobre enlace con pérdida |
| `SchedulerOrderTest` | orden del modo 0 (RLCP + rate-distortion) y contigüidad de planos |
| `SchedulerForgetTest` | ciclo LRU olvido→reenvío |
| `RapidModesSchedulerTest` | orden y FORGET en modos 1 y 2 |
| `RapidSessionTest` | sesión en vivo: retransmit/FORGET/orden, reconstrucción en modos 0–2, cambio de modo, lotes, dos sesiones |
| `RapidClientTest`, `js_verify.mjs`, `forget_verify.mjs` | end-to-end sobre WebSocket real; port JS idéntico a Java |

Resultado observado del transporte (2000 segmentos, enlace simulado): entrega íntegra en todos
los escenarios; `cwnd` crece sin pérdida y se reduce ante ella; 0 retransmisiones espurias en
red ordenada. Imagen real de 17 GB: números legibles a máxima resolución (reconstrucción sin
pérdida) y borrosos con pocas capas (progresivo confirmado).

---

## 13. Tabla de parámetros

| Parámetro | Valor | Ref. |
|-----------|-------|------|
| Tile / niveles / precinct (.h2k) | 512 / 5 / 64 | §3.1 |
| Escala del overview | 2^levels = 32 | §3.3 |
| Umbral de ACK duplicados | 3 | §8.3 |
| `cwnd` / `ssthresh` iniciales | 1 / 64 segmentos | §8.3 |
| RTO | `[200 ms, 60 s]` | §8.4 |
| Retransmisiones SACK por ACK | ≤ 4 | §8.2 |
| `rwnd` (modo 0 / modos 1-2) | `[8,256]` / `[1,16]` seg. | §8.5 |
| Cola de escritura WebSocket | 1 MiB | §8.5 |
| Lote: paquetes / bytes | ≤ 16 / ≤ 32 768 | §8.6 |
| DRR entre sesiones: tick / quantum | 20 ms / ~32 KiB | §8.7 |
| Umbrales de carga | 0.70 (prefetch) / 0.85 (refinamiento) | §8.8 |
| Token bucket de prefetch | 65 536 B/s | §9.0 |
| Deadlines (modo 1) | 250 ms (grueso) / 1000 ms (base) | §9.2 |
| Fragmento de tile ZIP | 16 000 B | §4.3 |
| Caché cliente | 160 tiles / 256 MiB | §10 |
| Ventana de predicción | 1.5 s, ≤8 muestras | §9.3 |

---

## 14. Referencias

1. **RFC 9293** — *Transmission Control Protocol (TCP)*, IETF 2022.
2. **RFC 5681** — *TCP Congestion Control* (slow start, CA, fast recovery).
3. **RFC 2018** — *TCP Selective Acknowledgment Options (SACK)*.
4. **RFC 6675** — *Loss Recovery Using SACK* (regla *IsLost*).
5. **RFC 6298** — *Computing TCP's Retransmission Timer*; Karn & Partridge (1987).
6. **RFC 6455** — *The WebSocket Protocol*.
7. **RFC 1951** — *DEFLATE Compressed Data Format*.
8. **ISO/IEC 15444-1** — *JPEG 2000* (niveles de resolución, precincts, quality layers,
   transformada 5/3 reversible).
9. Calderbank et al. — *Wavelet Transforms That Map Integers to Integers* (1998).
10. D. Hilbert — *Über die stetige Abbildung einer Linie auf ein Flächenstück* (1891).
11. C. L. Liu, J. W. Layland — *Scheduling Algorithms for Multiprogramming in a Hard-Real-Time
    Environment* (1973) — **EDF**.
12. M. Shreedhar, G. Varghese — *Efficient Fair Queuing Using Deficit Round Robin* (1996).
13. Cardwell et al. — *BBR: Congestion-Based Congestion Control* (2016) — contexto.
14. Microsoft — *Deep Zoom / DZI file format*; **libvips** `dzsave`.

---

## 15. Glosario

**ARQ**: retransmisión automática ante pérdida. **SACK**: confirmación selectiva.
**Selective Repeat**: retransmitir solo lo faltante. **cwnd/rwnd**: ventana de congestión /
de recepción. **RTO/RTT**: tiempo de retransmisión / de ida y vuelta. **Precinct**: región
espacial de coeficientes. **Quality layer / bit-plane**: plano de bits que refina la calidad.
**DRR**: Deficit Round Robin (equidad). **EDF**: Earliest Deadline First. **DZI**: Deep Zoom
Image (pirámide de tiles). **Overview**: miniatura de toda la imagen.
