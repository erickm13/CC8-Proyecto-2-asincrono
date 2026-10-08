# Project Core Technologies

## Languages and Runtimes

Java 21 powers the server; the browser client uses JavaScript.

## Frameworks and Libraries

The server uses JDK asynchronous networking and has no external runtime
dependencies. WebSocket framing is implemented in the project. Optional ZIP
preprocessing uses `libvips`; serving an already generated ZIP does not require it.

## Build, Test, and Development Tools

`compile.sh` invokes `javac` into `out/` and supports offline compilation.

## External Services and Infrastructure

No external services are required by the documented runtime.

## Important Technical Constraints

The server defaults to port 8080, `public/`, and `images/sample.h2k`. H2K v1
remains unchanged. The JDK `ZipFile` reader supports lossless ZIP64 Deep Zoom
archives of PNG tiles across resolution levels, without H2K bit-plane quality
layers. H2K sessions use HELLO v1; ZIP sessions use HELLO v2 and `ZT` fragments
with a 22-byte header and up to 16,000 PNG bytes each.
Legacy no-HELLO connections use H2K mode 0. RAPID modes 0–2 share DATA/ACK/SACK;
modes 1 and 2 batch up to 16 packets per DATA within a 32 KiB aggregate payload.
See `docs/PROTOCOLO-CC8.md` for the wire format.

The JDK H2K preprocessor streams PNG rows and writes tiles in order. The optional
libvips route creates a lossless PNG Deep Zoom ZIP, defaulting to 512-pixel tiles,
no overlap, PNG compression 0, and no outer ZIP compression.

The H2K browser latency panel shows rolling medians over up to 64 samples and counts
since session reset or mode change. Its seven client-only `performance.now()` spans
cover scheduled view-change-to-VIEWPORT send, VIEWPORT-to-first intersecting tile
packet, receive-to-inflate completion, inflate-ready-to-visible-reconstruction start,
reconstruction duration, `draw()` duration, and VIEWPORT-to-first draw of a tile
reconstructed with a later packet. The scheduled-send metric excludes initial-session
and pan-decay sends. The two VIEWPORT spans are approximate because DATA has no
request ID. The metrics do not synchronize server/client clocks, and `draw()` does
not measure display.
