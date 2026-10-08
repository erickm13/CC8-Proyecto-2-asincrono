# Project Structure

## Directory Layout

- `src/main/java/com/cc8/server/`: Java server and image modules.
- `public/`: browser entry point, JavaScript modules, and styles.
- `test/`: JavaScript verification scripts.
- `docs/PROTOCOLO-CC8.md`: RAPID and image-format specification.
- `compile.sh`, `run.sh`, `run_tests.sh`, `preprocess_vips.sh`,
  `run_vips_tests.sh`: build, launch, preprocessing, and verification scripts.

## Modules and Responsibilities

- `Main.java` composes HTTP handlers, routing, image readers, and streaming.
- `http/` provides the asynchronous HTTP server; `handler/` routes requests and
  serves static files.
- `protocol/` implements RAPID sending, receiving, mode scheduling, shared packet
  reads, wire messages, and image protocol handling; `ws/` handles WebSocket
  framing and sessions.
- `image/` handles H2K encoding/decoding and ZIP Deep Zoom PNG tile reading.
  H2K PNG preprocessing streams rows; `preprocess_vips.sh` optionally creates
  the ZIP pyramid with libvips.
- `public/js/` contains the browser app and RAPID mode selector, decoder,
  receiver, wire modules, and ZIP PNG tile viewer.
- `public/index.html` contains the collapsed client-latency diagnostics panel;
  the H2K app in `public/js/app.js` records its seven measurements.

## Main Interfaces and Integration Boundaries

`Main` connects the server modules and exposes `GET /health`, `/api/manifest`,
`/api/overview`, and the `/stream` WebSocket endpoint. `public/index.html` loads
the browser app from `public/js/app.js`; client and server protocol details are
defined in `docs/PROTOCOLO-CC8.md`.

## Tests and Supporting Assets

`run_tests.sh` runs the H2K suite; `run_vips_tests.sh` checks a libvips ZIP
through the live server and all three modes. JavaScript verifiers require Node.js.
`images/sample.h2k` remains the default image.
