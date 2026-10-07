import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DATA_FLAG_BATCH, T, encodeHello, encodeViewport, parseDataPackets } from '../public/js/wire.js';
import { Receiver } from '../public/js/receiver.js';

function imagePacket(tile = 0) {
    const packet = new Uint8Array(19);
    const dv = new DataView(packet.buffer);
    dv.setUint32(0, tile);
    packet[4] = 0; packet[5] = 0;
    packet[10] = 0; packet[11] = 1;
    dv.setUint32(12, 1); dv.setUint16(16, 1);
    packet[18] = 0;
    return packet;
}

function dataSegment(seq, flags, payload) {
    const segment = new ArrayBuffer(16 + payload.length);
    const dv = new DataView(segment);
    const u8 = new Uint8Array(segment);
    u8[0] = T.DATA;
    dv.setUint32(1, seq);
    u8[13] = flags;
    dv.setUint16(14, payload.length);
    u8.set(payload, 16);
    return segment;
}

function batchPayload(packets) {
    const length = packets.reduce((sum, p) => sum + 2 + p.length, 0);
    const payload = new Uint8Array(length);
    const dv = new DataView(payload.buffer);
    let offset = 0;
    for (const packet of packets) {
        dv.setUint16(offset, packet.length);
        payload.set(packet, offset + 2);
        offset += 2 + packet.length;
    }
    return payload;
}

assert.deepEqual([...new Uint8Array(encodeHello(2))], [1, 1, 2]);
assert.throws(() => encodeHello(3), RangeError);
const viewport = new DataView(encodeViewport(1, 2, 3, 4, 5,
    { dx: -300, dy: 28, confidence: 300 }));
assert.equal(viewport.byteLength, 21);
assert.deepEqual([viewport.getInt8(18), viewport.getInt8(19), viewport.getUint8(20)], [-127, 28, 255]);

const legacy = imagePacket();
assert.equal(parseDataPackets(legacy, 0).length, 1);
const batch = parseDataPackets(batchPayload(Array.from({ length: 16 }, (_, i) => imagePacket(i))), DATA_FLAG_BATCH);
assert.equal(batch.length, 16);
assert.throws(() => parseDataPackets(batchPayload(Array.from({ length: 17 }, () => imagePacket())), DATA_FLAG_BATCH), RangeError);
assert.throws(() => parseDataPackets(new Uint8Array(32769), DATA_FLAG_BATCH), RangeError);

const delivered = [], acks = [];
const transport = new Receiver(ack => acks.push(ack), packet => delivered.push(packet), 16);
transport.onData(dataSegment(0, 0, legacy));
transport.onData(dataSegment(1, DATA_FLAG_BATCH, batchPayload(Array.from({ length: 16 }, () => imagePacket()))));
assert.equal(delivered.length, 17);
assert.equal(transport.delivered, 17);
assert.equal(transport.rcvNxt, 2);
assert.equal(acks.length, 2);

const html = readFileSync(new URL('../public/index.html', import.meta.url), 'utf8');
for (const name of ['RAPID Progresivo (Hilbert)', 'RAPID Cobertura EDF', 'RAPID Predictivo DRR']) assert.ok(html.includes(name));

// Import the browser app against small DOM/WebSocket shims to cover mode reset,
// view preservation, and a decode finishing after its socket has gone stale.
const nativeSetTimeout = globalThis.setTimeout.bind(globalThis);
let now = 1000, timerId = 0;
const fakeTimers = new Map(), intervals = [], sockets = [], inflations = [];
Object.defineProperty(globalThis, 'performance', { configurable: true, value: { now: () => now } });
globalThis.setTimeout = (fn) => { const id = ++timerId; fakeTimers.set(id, fn); return id; };
globalThis.clearTimeout = id => fakeTimers.delete(id);
function fireNextTimer() {
    const next = fakeTimers.entries().next().value;
    assert.ok(next, 'expected a scheduled timer');
    fakeTimers.delete(next[0]);
    next[1]();
}
globalThis.setInterval = fn => { intervals.push(fn); return intervals.length; };
globalThis.requestAnimationFrame = () => 1;
globalThis.location = { host: 'localhost' };
globalThis.fetch = async () => ({ json: async () => ({
    width: 4000, height: 3000, components: 1, levels: 4, tileSize: 64,
    tilesX: 63, tilesY: 47, hasOverview: false,
}) });
globalThis.DecompressionStream = class HeldDecompressionStream {
    constructor(format) {
        assert.equal(format, 'deflate-raw');
        let controller;
        this.readable = new ReadableStream({ start(c) { controller = c; } });
        this.writable = new WritableStream({ write() {}, close() {} });
        inflations.push(() => { controller.enqueue(new Uint8Array(2 * 1024 * 1024)); controller.close(); });
    }
};

class MockWebSocket {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(url) { this.url = url; this.readyState = MockWebSocket.CONNECTING; this.sent = []; sockets.push(this); }
    send(buffer) { this.sent.push(buffer); }
    open() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
    close() { this.readyState = MockWebSocket.CLOSED; this.onclose?.(); }
}
globalThis.WebSocket = MockWebSocket;

const canvasListeners = new Map(), windowListeners = new Map();
const context = { fillRect() {}, drawImage() {}, createImageData(w, h) { return { data: new Uint8ClampedArray(w * h * 4) }; }, putImageData() {} };
const canvas = {
    clientWidth: 800, clientHeight: 600, width: 0, height: 0,
    getContext: () => context,
    getBoundingClientRect: () => ({ left: 0, top: 0 }),
    addEventListener: (type, fn) => canvasListeners.set(type, fn),
};
const elements = new Map([
    ['view', canvas],
    ['status', { textContent: '', className: '' }],
    ['stats', { textContent: '' }],
    ['mode', { value: '0', addEventListener(type, fn) { this.listener = fn; } }],
    ['fit', { addEventListener() {} }],
]);
globalThis.document = {
    getElementById: id => elements.get(id),
    createElement: () => ({ width: 0, height: 0, getContext: () => context }),
};
globalThis.window = { addEventListener: (type, fn) => windowListeners.set(type, fn) };

await import('../public/js/app.js?client_modes_test');
await new Promise(resolve => nativeSetTimeout(resolve, 0));
assert.equal(sockets.length, 1);
const hilbertSocket = sockets[0];
hilbertSocket.open();
assert.deepEqual([...new Uint8Array(hilbertSocket.sent[0])], [1, 1, 0]);
hilbertSocket.onmessage({ data: dataSegment(0, 0, imagePacket(0)) });
const hilbertAck = [...hilbertSocket.sent].reverse().find(b => new Uint8Array(b)[0] === T.ACK);
assert.equal(new DataView(hilbertAck).getUint32(5), 256, 'mode 0 keeps the legacy receive window');
await new Promise(resolve => nativeSetTimeout(resolve, 0));
inflations.shift()();
await new Promise(resolve => nativeSetTimeout(resolve, 20));

for (let i = 0; i < 4; i++) {
    now += 200;
    canvasListeners.get('wheel')({ preventDefault() {}, clientX: 400, clientY: 300, deltaY: -1 });
}
const lastViewport = () => [...sockets.at(-1).sent].reverse().find(b => new Uint8Array(b)[0] === T.VIEWPORT);
const oldView = new DataView(lastViewport());
assert.ok(oldView.getUint32(1) > 0, 'zoomed viewport should have moved into the image');

const mode = elements.get('mode');
mode.value = '2';
mode.listener();
assert.equal(hilbertSocket.readyState, MockWebSocket.CLOSED);
assert.equal(sockets.length, 2);
const predictiveSocket = sockets[1];
predictiveSocket.open();
assert.deepEqual([...new Uint8Array(predictiveSocket.sent[0])], [1, 1, 2]);
const preservedView = new DataView(lastViewport());
assert.equal(preservedView.getUint32(1), oldView.getUint32(1));
assert.equal(preservedView.getUint32(5), oldView.getUint32(5));

canvasListeners.get('mousedown')({ clientX: 200, clientY: 200 });
now += 200;
windowListeners.get('mousemove')({ clientX: 180, clientY: 200 });
const predicted = new DataView(lastViewport());
assert.equal(predicted.byteLength, 21);
assert.ok(predicted.getInt8(18) > 0);
assert.ok(predicted.getUint8(20) > 0);
const confidenceAtPan = predicted.getUint8(20);
now += 500;
fireNextTimer();
const decayed = new DataView(lastViewport());
assert.ok(decayed.getUint8(20) < confidenceAtPan, 'idle viewport updates lower prediction confidence');

predictiveSocket.onmessage({ data: dataSegment(0, 0, imagePacket(0)) });
const predictiveAck = [...predictiveSocket.sent].reverse().find(b => new Uint8Array(b)[0] === T.ACK);
assert.equal(new DataView(predictiveAck).getUint32(5), 16, 'batch modes cap the receive window at 16 segments');
await new Promise(resolve => nativeSetTimeout(resolve, 0));
assert.equal(inflations.length, 1);
inflations.shift()();
await new Promise(resolve => nativeSetTimeout(resolve, 20));
intervals[0]();
assert.match(elements.get('stats').textContent, /paquetes: 1/);
assert.match(elements.get('stats').textContent, /caché: 2\.0\/256 MiB/);

predictiveSocket.onmessage({ data: dataSegment(1, 0, imagePacket(1)) });
await new Promise(resolve => nativeSetTimeout(resolve, 0));
assert.equal(inflations.length, 1);
mode.value = '1';
mode.listener();
assert.equal(predictiveSocket.readyState, MockWebSocket.CLOSED);
assert.equal(sockets.length, 3);
const edfSocket = sockets[2];
edfSocket.open();
assert.deepEqual([...new Uint8Array(edfSocket.sent[0])], [1, 1, 1]);
intervals[0]();
assert.match(elements.get('stats').textContent, /paquetes: 0/);
assert.match(elements.get('stats').textContent, /caché: 0\/160 tiles/);

// Old socket events and its pending decode cannot repopulate the reset cache.
predictiveSocket.onmessage({ data: dataSegment(2, 0, imagePacket(2)) });
inflations.shift()();
await new Promise(resolve => nativeSetTimeout(resolve, 20));
intervals[0]();
assert.match(elements.get('stats').textContent, /paquetes: 0/);
assert.match(elements.get('stats').textContent, /caché: 0\/160 tiles/);

console.log('OK client mode, batch framing, viewport prediction, and stale-session reset');
