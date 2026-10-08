# Project Diary

## Decisions and Lessons

- `run.sh` forwards the optional third image path (`.h2k` or Deep Zoom ZIP)
  accepted by `Main`.
- The same `.h2k` and RAPID DATA/ACK/SACK transport serve all three modes;
  HELLO v1 selects a mode, while connections without HELLO use mode 0.
- Prepared but unsent packets must be returned to the scheduler before a
  viewport rebuild. Only transmitted segments remain in the ACK/retransmission
  buffer, so a view change does not discard reliable delivery state.
- B's directional prefetch must shrink as confidence falls; its optional detail
  must resume when measured session load drops. Both behaviors have focused
  regression checks.
- A's 250 ms coarse and 1000 ms base deadlines are measured targets at send
  time, not strict delivery guarantees. Load is currently estimated from the
  number of sessions, capped at 16, rather than system utilization.
- For preprocessing, phase timers must keep output-call time separate from
  transform/encode work; otherwise per-plane writes are counted twice. A
  4096×2048 crop of the original PNG with production geometry (512/5/64)
  favored DEFLATE level 6 over 9: about 6.5% lower median total time with
  about 0.056% more bytes. Keep level 9 selectable for size-first runs.
- Parallelize tile encoding after each PNG strip is read. Bound in-flight tiles
  to the worker count, write their payloads and indexes in original order, and
  finish the strip before reusing its buffer. Four workers nearly halved crop
  wall time while preserving byte-identical H2K output. Aggregate worker
  transform time may exceed wall time because workers overlap.
- Browser latency must be measured on the client's monotonic clock. A RAPID
  packet has no viewport request ID, so viewport-to-visible-packet and
  viewport-to-first-fresh-draw figures can only be estimates when older DATA
  is in flight. Keep packet inflation, render wait, reconstruction, and canvas
  call timing separate; a canvas call does not prove physical presentation.
  Measure the most recent view change to actual VIEWPORT send separately to
  expose client scheduling delay without mixing it into post-send wait.
- A full-image timing line must distinguish wall time from summed worker time.
  The 8-worker run took 2,205.768 s wall time; its 15,949.588 s of
  transform/encode work is the sum across workers (7.23 worker-seconds per
  wall-second), while read and output-write calls took 108.474 and 207.880 s.
  The 7,542.5 s first run is a different configuration and cannot provide
  comparable phase times.
- This specific 136,325² RGB8 PNG has only stored DEFLATE blocks and filter-0
  rows across its full structure. That permits investigating direct pixel
  indexing, but PNG generally has one zlib stream with arbitrary IDAT
  boundaries, so never infer the fast path from the file extension or first
  block. The structural scan did not validate CRC/Adler checksums.
- The current full H2K contains 113,484,170 independently compressed layers;
  tuning DEFLATE level alone did not materially change 4-worker crop wall
  time. Reduce the number of encode operations or remove them for a large
  preprocessing gain, then measure output size and viewport latency.
- libvips is a viable third-party path for streaming image pyramids, but its
  default `dzsave` tiles are lossy JPEG. For exact source pixels, select
  lossless PNG tiles explicitly, avoid tile overlap, and verify its PNG loader
  accepts this source's 6.8 million IDAT chunks. An archive of tiles needs a
  new RAPID packet reader and browser decode path; generating it alone does
  not make it compatible with the existing H2K reader.
- For this RGB8 source, libvips `dzsave` with PNG tiles, zero overlap,
  `depth=onetile`, `[unlimited]` PNG load, and outer ZIP compression 0 created
  a lossless ZIP64 pyramid in 280 s at eight-way concurrency. `depth=onetile`
  provides one coarse overview tile and renumbers pyramid levels from zero.
  The resulting 74.5 GB ZIP has 95,301 entries; a JDK `ZipFile` reader can
  serve it without extracting the archive.
- A ZIP pyramid does not contain H2K bit-plane quality layers, so its three
  RAPID modes schedule resolution tiles and fragments using the existing
  reliable transport. Exact RGB was checked on a 1200×800 fixture in all
  three modes; the full 136325² artifact was checked by ZIP64 open and a live
  edge-tile request in each mode. Keep those acceptance scopes distinct.
