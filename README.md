# Proyecto 2 — Servidor Asíncrono de Imágenes (CC VIII)

Servidor asíncrono en **Java 21** que sirve imágenes de ultra alta resolución
(decenas de GB) a un navegador con **carga progresiva y selectiva**, mediante un
protocolo de transporte propio llamado **RAPID** (*Reliable Adaptive Progressive
Image Delivery*) sobre WebSocket.

Autor: **Erick Eleazar Mejía Moscoso**. El servidor y la ruta `.h2k` usan solo
el JDK y se compilan sin conexión. La creación opcional de ZIP Deep Zoom requiere
`libvips` (`vips`).

📄 **Especificación del protocolo:** [`docs/PROTOCOLO-CC8.md`](docs/PROTOCOLO-CC8.md)

---

## Requisitos

- **Java 21** (`java`, `javac` en el PATH).
- *(Opcional)* **Node.js ≥ 20** solo para las pruebas del cliente JS.
- *(Opcional)* **libvips** (`vips`) para generar ZIP Deep Zoom.

## Compilar y ejecutar

```bash
./compile.sh                               # compila a out/
./run.sh 8080 public images/sample.h2k     # compila y arranca el servidor
```
Abre **http://localhost:8080**: la imagen carga borrosa y se refina; rueda = zoom,
arrastrar = desplazar. El selector **Modo RAPID** ofrece `RAPID Progresivo
(Hilbert)`, `RAPID Cobertura EDF` y `RAPID Predictivo DRR`; cambiarlo reinicia la
sesión de transporte y conserva el mismo archivo `.h2k` o ZIP. En `run.sh`, el
tercer argumento acepta la ruta de cualquiera de esos archivos.
El panel superior muestra la telemetría del protocolo. Abre **Diagnóstico de latencia
del cliente**, debajo de esa telemetría, para ver siete medianas: último cambio de
vista a envío de `VIEWPORT`, `VIEWPORT` al primer paquete posterior de un tile que
intersecta la vista (aprox.), recepción a fin de `inflate`, `inflate` listo a inicio de
reconstrucción de tile visible, duración de esa reconstrucción, duración de `draw()`
y `VIEWPORT` al primer `drawImage()` de tile reconstruido con un paquete posterior
(aprox.). El panel muestra milisegundos y `n` (muestras desde el reinicio de sesión o
cambio de modo), con medianas de las últimas 64. El cambio a envío mide desde el
último cambio de vista programado; un valor alto indica espera de programación en el
cliente. El `VIEWPORT` a paquete refleja la espera y entrega de datos después del
envío. Valores altos en recepción→`inflate`, `inflate` listo→reconstrucción o
reconstrucción apuntan a decodificación, cola de renderizado o cálculo en el
navegador; `draw()` mide el costo de la llamada. Todo usa `performance.now()` del
cliente; no sincroniza relojes con el servidor. Las medidas desde `VIEWPORT` son
aproximadas porque RAPID no identifica qué solicitud originó cada paquete; `draw()`
mide la llamada del cliente, no la presentación en pantalla. Envíos iniciales, de
decaimiento de paneo, deduplicados o cubiertos por el overview no generan muestra de
cambio a envío.

## Procesar (preprocesar) una imagen

El servidor sirve archivos `.h2k` y ZIP Deep Zoom de tiles PNG. Para crear un
`.h2k` desde un PNG con el JDK:

```bash
# PNG pequeño o gigante (se lee por streaming, sin cargarlo en RAM):
java -cp out com.cc8.server.image.Preprocessor entrada.png salida.h2k
# luego:
./run.sh 8080 public salida.h2k
```

El preprocesador usa DEFLATE nivel 6 y hasta 4 hilos por defecto. Se pueden
ajustar con `--compression-level=0..9` y `--threads=N`; el nivel 9 puede reducir
ligeramente el tamaño a costa de más tiempo. Los PNG se leen fila a fila; los
tiles se codifican en paralelo de forma acotada y se escriben en orden H2K.
Al terminar, el comando informa tiempos de lectura, transformación/codificación,
escritura y tiempo total.

Como ruta opcional, `preprocess_vips.sh` crea con libvips una pirámide Deep Zoom
de PNG sin pérdida dentro de un solo ZIP. Usa tiles de 512 px, sin solapamiento y
sin compresión externa del ZIP. El comando `vips` y sus librerías deben estar
disponibles; usa `VIPS_BIN` si el ejecutable no está en `PATH`. Acepta
`--png-compression=0..9` y `--tile-size=N`.

```bash
VIPS_CONCURRENCY=8 ./preprocess_vips.sh \
  Imagen-55GB-comprimida/055-843-000-80450114.png images/large_vips.zip \
  --png-compression=0 --tile-size=512
./run.sh 8080 public images/large_vips.zip
```

Ambos formatos usan las tres políticas RAPID. La pirámide ZIP tiene niveles de
resolución, pero no capas de calidad por bit-plane como H2K; no modifica el
formato H2K ni requiere regenerar un H2K existente.

Ejemplo con las imágenes del curso (PNG 8-bit RGB, hasta 93 GB):
```bash
java -Xmx2g -cp out com.cc8.server.image.Preprocessor 017-110-000-24650032.png img17.h2k
```

## Ejecutar todas las pruebas

```bash
./run_tests.sh      # unitarias + integración + end-to-end sobre el servidor real
./run_vips_tests.sh # integración ZIP generado por libvips en los tres modos
```

---

## Arquitectura

```
Navegador (public/)                         Servidor (Java, src/)
 ┌───────────────────────────┐    HTTP      ┌──────────────────────────────┐
 │ app.js  visor + canvas     │◄───inicial──►│ http/   servidor async NIO.2 │
 │ receiver.js  ACK/SACK      │              │ ws/     WebSocket (RFC 6455) │
 │ decode.js  inflate+iDWT    │◄══ RAPID ═══►│ protocol/ transporte RAPID + │
 │ selector, zip_viewer.js    │  (WebSocket) │ scheduler RAPID + DRR bytes │
 │ caché + FORGET             │              │ image/ H2K + ZIP Deep Zoom   │
 └───────────────────────────┘              └──────────────────────────────┘
```

**Capas:**
1. **`image/`** — `.h2k` usa DWT Haar reversible, precincts, capas de calidad y
   DEFLATE. ZIP Deep Zoom contiene tiles PNG sin pérdida y niveles de resolución,
   sin capas de calidad bit-plane. La ruta H2K del JDK lee PNG por filas; ZIP
   opcional se crea con libvips.
2. **`http/`** — servidor HTTP asíncrono (`AsynchronousServerSocketChannel`),
   multicliente, sirve el sitio y hace el *upgrade* a WebSocket.
3. **`ws/`** — WebSocket propio (handshake + framing, RFC 6455).
4. **`protocol/`** — **RAPID**: ventana deslizante, SEQ/ACK/**SACK**,
   **Selective Repeat**, slow start, control de flujo y scheduler seleccionable
   (Hilbert, Cobertura EDF o Predictivo DRR). El dispatcher reparte bytes entre sesiones.
5. **`public/`** — cliente web: port fiel del receptor/decodificador a JavaScript.

## Mapa de requisitos → implementación

| Requisito del enunciado | Dónde |
|-------------------------|-------|
| Servidor Java asíncrono multicliente | `http/HttpServer` (NIO.2) |
| Protocolo propio de control | `protocol/` (RAPID) + `docs/PROTOCOLO-CC8.md` |
| Mecanismos tipo TCP (SACK, Selective Repeat, slow start, flow/congestion) | `protocol/ReliableSender`, `ReliableReceiver` |
| HTTP inicial + protocolo propio para la imagen | `ws/` (upgrade) + `protocol/Wire` |
| Sitio y streaming servidos por Java sin recursos externos en ejecución | `handler/StaticFileHandler`, librerías propias |
| Carga progresiva/selectiva (transferir y eliminar info) | `image/` (capas) + `protocol/Scheduler` + LRU/`FORGET` |
| No saturar el navegador | caché LRU + flow control (`rwnd`) |
| Imágenes grandes y vista alejada | Preprocesamiento PNG por filas o pirámide ZIP + overview (`GET /api/overview`) |

## Pruebas incluidas

| Prueba | Qué valida |
|--------|------------|
| `DwtSelfTest` | DWT Haar reversible exacta |
| `BitPlaneSelfTest` | capas de calidad: error → 0 |
| `RoundTripTest` / `PngIngestTest` | formato `.h2k` sin pérdida; ingesta PNG == ImageIO |
| `HilbertSelfTest` | curva de Hilbert (biyección + localidad) |
| `WebSocketSelfTest` | handshake (ejemplo RFC 6455) + frames |
| `TransportSelfTest` | entrega 100% bajo pérdida 10–30% (SACK, congestión) |
| `FlowControlTest` | el emisor nunca excede `rwnd` (control de flujo) |
| `IntegrationSelfTest` | scheduler→transporte→reconstrucción sin pérdida |
| `SchedulerOrderTest` | orden del scheduler: resolución + capa + utilidad/byte (rate-distortion) |
| `SchedulerForgetTest` | ciclo LRU olvido→reenvío |
| `RapidClientTest`, `test/js_verify.mjs`, `test/forget_verify.mjs` | end-to-end sobre WebSocket real |
| `run_vips_tests.sh`, `ZipPyramidIntegrationTest`, `test/zip_client_test.mjs` | ZIP Deep Zoom generado con libvips y tres modos RAPID |
