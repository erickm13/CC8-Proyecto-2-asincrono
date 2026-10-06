# Proyecto 2 — Servidor Asíncrono de Imágenes (CC VIII)

Servidor asíncrono en **Java 21** que sirve imágenes de ultra alta resolución
(decenas de GB) a un navegador con **carga progresiva y selectiva**, mediante un
protocolo de transporte propio llamado **RAPID** (*Reliable Adaptive Progressive
Image Delivery*) sobre WebSocket.

Autor: **Erick Eleazar Mejía Moscoso**. Sin dependencias externas (solo el JDK) —
se compila y evalúa **sin conexión a internet**.

📄 **Especificación del protocolo:** [`docs/PROTOCOLO-CC8.md`](docs/PROTOCOLO-CC8.md)

---

## Requisitos

- **Java 21** (`java`, `javac` en el PATH).
- *(Opcional)* **Node.js ≥ 20** solo para las pruebas del cliente JS.

## Compilar y ejecutar

```bash
./compile.sh                               # compila a out/
./run.sh 8080 public images/sample.h2k     # compila y arranca el servidor
```
Abre **http://localhost:8080**: la imagen carga borrosa y se refina; rueda = zoom,
arrastrar = desplazar. El panel superior muestra la telemetría del protocolo.

## Procesar (preprocesar) una imagen

El servidor sirve archivos `.h2k`. Para generar uno desde un PNG:

```bash
# PNG pequeño o gigante (se lee por streaming, sin cargarlo en RAM):
java -cp out com.cc8.server.image.Preprocessor entrada.png salida.h2k
# luego:
./run.sh 8080 public salida.h2k
```
Ejemplo con las imágenes del curso (PNG 8-bit RGB, hasta 93 GB):
```bash
java -Xmx2g -cp out com.cc8.server.image.Preprocessor 017-110-000-24650032.png img17.h2k
```

## Ejecutar todas las pruebas

```bash
./run_tests.sh      # unitarias + integración + end-to-end sobre el servidor real
```

---

## Arquitectura

```
Navegador (public/)                         Servidor (Java, src/)
 ┌───────────────────────────┐    HTTP      ┌──────────────────────────────┐
 │ app.js  visor + canvas     │◄───inicial──►│ http/   servidor async NIO.2 │
 │ receiver.js  ACK/SACK      │              │ ws/     WebSocket (RFC 6455) │
 │ decode.js  inflate+iDWT    │◄══ RAPID ═══►│ protocol/ transporte RAPID + │
 │ wire.js  frames binarios   │  (WebSocket) │          scheduler Hilbert   │
 │ caché LRU + FORGET         │              │ image/  formato .h2k + DWT   │
 └───────────────────────────┘              └──────────────────────────────┘
```

**Capas:**
1. **`image/`** — formato `.h2k`: DWT Haar reversible, precincts, capas de calidad
   (bit-planes), DEFLATE. Preprocesamiento por streaming (PNG fila a fila) para
   imágenes de decenas de GB sin cargarlas en RAM.
2. **`http/`** — servidor HTTP asíncrono (`AsynchronousServerSocketChannel`),
   multicliente, sirve el sitio y hace el *upgrade* a WebSocket.
3. **`ws/`** — WebSocket propio (handshake + framing, RFC 6455).
4. **`protocol/`** — **RAPID** (el núcleo, 60% de la nota): ventana deslizante,
   SEQ/ACK/**SACK**, **Selective Repeat**, slow start + control de congestión y de
   flujo, RTO; + scheduler (orden Hilbert + utilidad/deadline) que decide qué enviar.
5. **`public/`** — cliente web: port fiel del receptor/decodificador a JavaScript.

## Mapa de requisitos → implementación

| Requisito del enunciado | Dónde |
|-------------------------|-------|
| Servidor Java asíncrono multicliente | `http/HttpServer` (NIO.2) |
| Protocolo propio de control | `protocol/` (RAPID) + `docs/PROTOCOLO-CC8.md` |
| Mecanismos tipo TCP (SACK, Selective Repeat, slow start, flow/congestion) | `protocol/ReliableSender`, `ReliableReceiver` |
| HTTP inicial + protocolo propio para la imagen | `ws/` (upgrade) + `protocol/Wire` |
| Todo servido por el servidor Java (sin recursos externos) | `handler/StaticFileHandler`, librerías propias |
| Carga progresiva/selectiva (transferir y eliminar info) | `image/` (capas) + `protocol/Scheduler` + LRU/`FORGET` |
| No saturar el navegador | caché LRU + flow control (`rwnd`) |
| Imágenes de 24–93 GB | `image/PngStreamReader` (ingesta por streaming) + overview (`GET /api/overview`) para la vista alejada |

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
