# Project Progress

Deployment `libvips-rapid-zip-20261006` (Heavy route) is complete.

## Goal and Result

`preprocess_vips.sh` creates one lossless Deep Zoom PNG-tile ZIP. The Java
server and browser accept that ZIP in RAPID Progresivo (Hilbert), Cobertura
EDF, and Predictivo DRR. Existing H2K1 images and older clients remain
supported through their original protocol path.

## Verified State

The 136325×136325 course PNG (55,843,161,368 input bytes) produced
`images/large_vips.zip` in 280 s with libvips 8.15.1, eight-way concurrency,
512-pixel tiles, and PNG compression 0. The ZIP is 74,534,432,660 bytes and
contains 95,301 entries. Java opened it; a live WebSocket test received the
same far-edge tile in all three modes. On a 1200×800 fixture, the three modes
reconstructed every full-resolution tile with exact RGB pixels, and transport
batching, retransmission, and `FORGET` checks passed. The complete H2K and ZIP
test suite passed.

## Next Milestone

No implementation work remains for this deployment. Optional follow-up is to
measure viewport latency and try PNG compression levels 1–3 on a representative
crop to choose a smaller ZIP if storage or transfer cost matters.
