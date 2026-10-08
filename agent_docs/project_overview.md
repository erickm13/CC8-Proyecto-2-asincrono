# Project Overview

## Purpose

Provide a Java 21 asynchronous image server and browser client for progressive,
selective delivery of ultra-high-resolution images.

## Scope

The server serves the browser application and exposes health, image manifest,
overview, and WebSocket streaming endpoints. The client receives and renders
image data progressively.

## Architecture

`Main` wires the HTTP server, routes, static-file handler, image reader, and RAPID
stream handler. The browser supports H2K decoding and lossless PNG tiles from a
Deep Zoom ZIP. Both formats use Progresivo (Hilbert), Cobertura EDF, and Predictivo
DRR. H2K sessions use HELLO v1 or legacy mode 0 without HELLO; ZIP sessions use
HELLO v2. See `docs/PROTOCOLO-CC8.md`.

The H2K viewer has a collapsed client-latency panel with seven rolling medians and
sample counts. It separates the scheduled client delay before sending a VIEWPORT
from the approximate wait for a later intersecting DATA packet. It uses browser
`performance.now()` only; the two measurements from `VIEWPORT` are approximate
because DATA packets have no request ID.

## Main Workflows

Run `./compile.sh` offline, then `./run.sh [port] [webroot] [image.h2k|image.zip]`.
Defaults are port 8080, `public/`, and `images/sample.h2k`. `Preprocessor` creates
H2K with the JDK; optional `./preprocess_vips.sh input.png output.zip` creates a
lossless Deep Zoom PNG pyramid and requires libvips.

For H2K, `Preprocessor` streams PNG rows, encodes tiles with bounded parallel
workers, and writes them in order. Defaults are DEFLATE level 6 and four workers;
`--compression-level` and `--threads` override them.

## Major Decisions

RAPID scheduling is independent of the image container. H2K v1 remains supported
unchanged; ZIP sessions use HELLO v2 and transport PNG tile fragments through the
same DATA/ACK/SACK reliability layer. ZIP resolution levels do not include H2K
bit-plane quality layers.
Client diagnostics separate packet wait, inflate, visible-tile reconstruction,
and draw-call time; they do not synchronize server/client clocks or measure
screen presentation.
