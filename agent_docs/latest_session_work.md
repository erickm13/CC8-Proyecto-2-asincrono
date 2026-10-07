# Latest Session Work

Deployment `libvips-rapid-zip-20261006` is complete. The user requested a
libvips preprocessing path whose ZIP works in all three RAPID modes.

## Implementation

- `preprocess_vips.sh` invokes `vips dzsave` with PNG tiles, zero overlap,
  `depth=onetile`, no outer ZIP compression, and `[unlimited]` PNG loading for
  this source's many IDAT chunks. It writes to a temporary sibling path and
  renames on success. The source PNG and existing H2K were preserved.
- `ZipPyramidReader` validates DZI geometry and PNG tile entries, opens ZIP64
  through JDK `ZipFile`, and caches a bounded set of tile bytes.
- ZIP sessions negotiate `HELLO` version 2 and carry 16,000-byte tile fragments
  with a 22-byte `ZT` header through existing RAPID DATA/ACK/SACK transport.
  `ZipPyramidScheduler` supports all three policies, including EDF deadlines,
  B utility ordering and directional prefetch, and session-level DRR dispatch.
- `zip_viewer.js` assembles fragments, decodes PNG tiles, renders the pyramid,
  changes modes without losing view position, and bounds its cache. H2K1 stays
  on the original reader/browser path and version-1 negotiation.

## Verification

- `run_tests.sh` passed with isolated libvips 8.15.1 available through
  `VIPS_BIN`. It includes the old H2K tests and `run_vips_tests.sh`.
- `ZipPyramidIntegrationTest` used an odd-size 1200×800 PNG. Every full-level
  pixel matched source RGB in modes 0/1/2. It checked 32 KiB/16-packet batch
  bounds, a deliberately lost ACK and retransmission, and `FORGET` resend.
  `zip_client_test.mjs` passed fragment assembly and mode reset checks.
- The course source `Imagen-55GB-comprimida/055-843-000-80450114.png`
  (136325×136325; 55,843,161,368 bytes) produced
  `images/large_vips.zip` (74,534,432,660 bytes; 95,301 entries) in 280 s
  with eight-way concurrency, tile 512 and PNG compression 0. The Java reader
  opened its ZIP64 index. `ZipPyramidLargeSmoke` received the same 53,366-byte
  far-edge tile over live WebSocket in modes 0, 1, and 2 (70/34/33 DATA frames).
  This is a bounded live smoke test; full-image pixel comparison was performed
  on the smaller fixture.
- `git diff --check` passed. The generated ZIP and local libvips runtime are
  ignored by Git; the existing large H2K was not regenerated.

## Handoff

Run `./preprocess_vips.sh entrada.png salida.zip`, then
`./run.sh 8080 public salida.zip`. For tests, run `./run_tests.sh` with
libvips installed, or set `VIPS_BIN` and its library path to an isolated
runtime. The ZIP pyramid is lossless at full resolution but uses resolution
levels rather than the H2K bit-plane quality layers. PNG compression 0 favored
speed for this run and increased output from the 50.5 GB H2K to 74.5 GB.
