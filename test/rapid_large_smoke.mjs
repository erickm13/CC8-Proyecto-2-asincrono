// Bounded live smoke for the existing large .h2k: one DATA frame per mode.
// Run against a server started with images/large.h2k; this does no preprocessing.
import assert from 'node:assert/strict';
import { DATA_FLAG_BATCH, T, encodeAck, encodeHello, encodeViewport, parseData, parseDataPackets, parseImagePacket } from '../public/js/wire.js';
import { inflateRaw } from '../public/js/decode.js';

const httpBase = process.argv[2] || 'http://localhost:8100';
const timeoutMs = 15000;

async function main() {
    const manifestResponse = await fetch(`${httpBase}/api/manifest`);
    assert.equal(manifestResponse.status, 200, 'large image manifest request failed');
    const h = await manifestResponse.json();
    assert.equal(h.name, 'large.h2k');
    assert.equal(h.width, 136325);
    assert.equal(h.height, 136325);

    const tx = Math.floor(h.tilesX / 2), ty = Math.floor(h.tilesY / 2);
    const tile = ty * h.tilesX + tx;
    const x = tx * h.tileSize + Math.floor(h.tileSize / 2);
    const y = ty * h.tileSize + Math.floor(h.tileSize / 2);
    const viewport = encodeViewport(x, y, 64, 64, h.levels);

    for (const mode of [0, 1, 2]) {
        const result = await firstDataFrame(httpBase, mode, viewport, tile, h);
        console.log(`mode ${mode}: tile=${tile}, DATA bytes=${result.bytes}, image packets=${result.packetCount}, batch=${result.batch}, elapsed=${result.elapsedMs} ms`);
    }
    console.log('OK large.h2k live smoke: header, one visible-tile DATA frame, and image-packet framing validated in modes 0, 1, and 2');
}

function firstDataFrame(httpBase, mode, viewport, visibleTile, header) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(httpBase.replace(/^http/, 'ws') + '/stream');
        ws.binaryType = 'arraybuffer';
        const started = Date.now();
        const timer = setTimeout(() => finish(new Error(`mode ${mode} had no DATA within ${timeoutMs} ms`)), timeoutMs);
        let done = false;
        const finish = (error, result) => {
            if (done) return;
            done = true;
            clearTimeout(timer);
            try { ws.close(); } catch { /* already closed */ }
            if (error) reject(error);
            else resolve(result);
        };

        ws.onerror = event => finish(new Error(`mode ${mode} WebSocket error: ${event.message || 'unknown error'}`));
        ws.onclose = event => {
            if (!done) finish(new Error(`mode ${mode} WebSocket closed before DATA (code ${event.code})`));
        };
        ws.onopen = () => {
            if (mode !== 0) ws.send(encodeHello(mode));
            ws.send(viewport);
        };
        ws.onmessage = async event => {
            try {
                const bytes = event.data;
                const type = new Uint8Array(bytes, 0, 1)[0];
                if (type !== T.DATA) return;
                const data = parseData(bytes);
                const batch = Boolean(data.flags & DATA_FLAG_BATCH);
                const packets = parseDataPackets(data.payload, data.flags);
                assert.ok(packets.length > 0 && packets.length <= 16, 'DATA packet count is outside 1..16');
                if (batch) assert.ok(data.payload.byteLength <= 32768, 'batch payload exceeds 32768 bytes');
                const decoded = packets.map(parseImagePacket);
                assert.ok(decoded.some(p => p.tile === visibleTile),
                    `mode ${mode} first DATA did not include visible tile ${visibleTile}`);
                for (const p of decoded) {
                    assert.ok(p.tile < header.tilesX * header.tilesY, 'image packet tile outside manifest');
                    assert.ok(p.comp < header.components, 'image packet component outside manifest');
                    assert.ok(p.level <= header.levels, 'image packet level outside manifest');
                    assert.ok(p.numPlanes > 0 && p.layer < p.numPlanes, 'invalid image packet layer metadata');
                    assert.ok(p.data.byteLength > 0, 'empty compressed image packet');
                }
                const visiblePacket = decoded.find(p => p.tile === visibleTile);
                const inflated = await inflateRaw(visiblePacket.data);
                assert.ok(inflated.byteLength > 0, 'visible packet DEFLATE payload decoded to no bytes');
                ws.send(encodeAck(data.seq + 1, 16, data.sendTs, []));
                finish(null, {
                    bytes: bytes.byteLength,
                    packetCount: packets.length,
                    batch,
                    elapsedMs: Date.now() - started,
                });
            } catch (error) {
                finish(error);
            }
        };
    });
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
