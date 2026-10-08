import assert from 'node:assert/strict';
import { startZipViewer } from '../public/js/zip_viewer.js';
import { T, DATA_FLAG_BATCH } from '../public/js/wire.js';

function packet(tileId, level, tx, ty, part, parts, png) {
    const from = part * 16000;
    const data = png.subarray(from, Math.min(png.length, from + 16000));
    const out = new Uint8Array(22 + data.length);
    const dv = new DataView(out.buffer);
    out[0] = 0x5a; out[1] = 0x54; out[2] = 1;
    dv.setUint32(3, tileId); out[7] = level;
    dv.setUint16(8, tx); dv.setUint16(10, ty);
    dv.setUint16(12, part); dv.setUint16(14, parts);
    dv.setUint32(16, png.length); dv.setUint16(20, data.length);
    out.set(data, 22);
    return out;
}

function data(seq, flags, packets) {
    let payload;
    if (flags & DATA_FLAG_BATCH) {
        const size = packets.reduce((n, p) => n + 2 + p.length, 0);
        payload = new Uint8Array(size);
        const dv = new DataView(payload.buffer);
        let at = 0;
        for (const p of packets) {
            dv.setUint16(at, p.length); at += 2;
            payload.set(p, at); at += p.length;
        }
    } else payload = packets[0];
    const frame = new ArrayBuffer(16 + payload.length);
    const dv = new DataView(frame);
    const u8 = new Uint8Array(frame);
    u8[0] = T.DATA; dv.setUint32(1, seq);
    u8[13] = flags; dv.setUint16(14, payload.length);
    u8.set(payload, 16);
    return frame;
}

const sockets = [], draws = [], decoded = [], frames = [];
globalThis.location = { protocol: 'http:', host: 'localhost:8101' };
globalThis.performance = { now: () => 1000 };
globalThis.requestAnimationFrame = fn => { frames.push(fn); return frames.length; };
globalThis.setInterval = () => 1;
globalThis.createImageBitmap = async blob => {
    decoded.push(new Uint8Array(await blob.arrayBuffer()));
    return { width: 512, height: 512, close() {} };
};

class MockWebSocket {
    static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; sockets.push(this); }
    send(buffer) { this.sent.push(buffer); }
    open() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
    close() { this.readyState = MockWebSocket.CLOSED; this.onclose?.(); }
    receive(frame) { this.onmessage?.({ data: frame }); }
}
globalThis.WebSocket = MockWebSocket;

const listeners = new Map();
const canvas = {
    clientWidth: 800, clientHeight: 600, width: 0, height: 0,
    getContext: () => ({ fillRect() {}, drawImage: (...args) => draws.push(args) }),
    getBoundingClientRect: () => ({ left: 0, top: 0 }),
    addEventListener: (name, fn) => listeners.set(name, fn),
};
const mode = { value: '0', addEventListener(name, fn) { this.listener = fn; } };
const status = { textContent: '', className: '' };
const stats = { textContent: '' };
const elements = new Map([
    ['view', canvas], ['mode', mode], ['status', status], ['stats', stats],
    ['fit', { addEventListener() {} }],
]);
globalThis.document = { getElementById: id => elements.get(id) };
globalThis.window = { addEventListener() {} };

await startZipViewer({ format: 'vips-zip', width: 1200, height: 800,
    tileSize: 512, levels: 2, tilesX: 3, tilesY: 2, hasOverview: false });
assert.equal(sockets.length, 1);
const first = sockets[0];
first.open();
assert.deepEqual([...new Uint8Array(first.sent[0])], [T.HELLO, 2, 0]);
assert.equal(new Uint8Array(first.sent[1])[0], T.VIEWPORT);

const png = new Uint8Array(16001);
png.set([137, 80, 78, 71, 13, 10, 26, 10]);
for (let i = 8; i < png.length; i++) png[i] = i & 255;
const tileId = 3; // level 0:1 tile; level 1:2 tiles; first level-2 tile
first.receive(data(0, 0, [packet(tileId, 2, 0, 0, 1, 2, png)]));
assert.equal(decoded.length, 0, 'incomplete tile was decoded');
first.receive(data(1, 0, [packet(tileId, 2, 0, 0, 0, 2, png)]));
await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(decoded.length, 1);
assert.deepEqual(decoded[0], png, 'out-of-order PNG fragments were not joined exactly');
frames.shift()();
assert.ok(draws.length > 0, 'completed PNG tile was not drawn');

mode.value = '2'; mode.listener();
assert.equal(first.readyState, MockWebSocket.CLOSED);
assert.equal(sockets.length, 2);
const second = sockets[1];
second.open();
assert.deepEqual([...new Uint8Array(second.sent[0])], [T.HELLO, 2, 2]);
assert.equal(new DataView(second.sent[1]).byteLength, 21, 'mode B omitted viewport prediction');
const small = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
second.receive(data(0, DATA_FLAG_BATCH, [packet(tileId, 2, 0, 0, 0, 1, small)]));
await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(decoded.length, 2, 'batched ZIP packet was not decoded');
assert.equal(status.className, 'ok');

console.log('OK ZIP browser: HELLO v2, out-of-order tile assembly, draw, batching, mode reset');
