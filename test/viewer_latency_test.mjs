import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { T } from '../public/js/wire.js';

const html = readFileSync(new URL('../public/index.html', import.meta.url), 'utf8');
for (const id of [
    'latency-viewport-packet', 'latency-receive-inflate', 'latency-ready-reconstruct',
    'latency-reconstruction', 'latency-draw', 'latency-viewport-draw',
    'latency-view-change-send',
]) assert.ok(html.includes(`id="${id}"`), `missing diagnostic value ${id}`);
assert.match(html, /aprox\./i, 'viewport estimates need an approximation label');
assert.match(html, /siete valores son medianas de las últimas 64 muestras/);
assert.match(html, /no la presentación en pantalla/,
    'draw() must be described as a client call, not display presentation');
assert.match(html, /último cambio de vista → VIEWPORT enviado/);
assert.match(html, /excluye el envío inicial y el de decaimiento del paneo/);

const nativeSetTimeout = globalThis.setTimeout.bind(globalThis);
let now = 1000, timerId = 0, frameId = 0;
const fakeTimers = new Map(), intervals = [], frames = [], inflations = [];
const renderEvents = [];
Object.defineProperty(globalThis, 'performance', {
    configurable: true,
    value: { now: () => now },
});
globalThis.setTimeout = (fn, delay = 0) => {
    const id = ++timerId;
    fakeTimers.set(id, { fn, delay });
    return id;
};
globalThis.clearTimeout = id => fakeTimers.delete(id);
globalThis.setInterval = fn => { intervals.push(fn); return intervals.length; };
globalThis.requestAnimationFrame = fn => { frames.push(fn); return ++frameId; };
globalThis.location = { host: 'localhost' };
globalThis.fetch = async url => url === '/api/overview'
    ? { blob: async () => ({}) }
    : { json: async () => ({
        width: 4000, height: 3000, components: 1, levels: 4, tileSize: 64,
        precinct: 64, tilesX: 63, tilesY: 47, hasOverview: true,
    }) };
globalThis.createImageBitmap = async () => ({ width: 100, height: 75, overview: true });
globalThis.DecompressionStream = class HeldDecompressionStream {
    constructor(format) {
        assert.equal(format, 'deflate-raw');
        let controller;
        this.readable = new ReadableStream({ start(c) { controller = c; } });
        this.writable = new WritableStream({ write() {}, close() {} });
        inflations.push(() => { controller.enqueue(new Uint8Array([0])); controller.close(); });
    }
};

class MockWebSocket {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(url) {
        this.url = url;
        this.readyState = MockWebSocket.CONNECTING;
        this.sent = [];
        sockets.push(this);
    }
    send(buffer) { this.sent.push({ buffer, at: now }); }
    open() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
    close() { this.readyState = MockWebSocket.CLOSED; this.onclose?.(); }
}
const sockets = [];
globalThis.WebSocket = MockWebSocket;

const canvasListeners = new Map(), windowListeners = new Map();
const context = {
    fillRect() { renderEvents.push({ type: 'fillRect' }); now += 2; },
    drawImage(source, ...args) {
        renderEvents.push({ type: 'drawImage', source, args });
        now += source?.overview ? 1 : 3;
    },
    createImageData(w, h) { return { data: new Uint8ClampedArray(w * h * 4) }; },
    putImageData() { renderEvents.push({ type: 'putImageData' }); },
};
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
    ...[
        'latency-viewport-packet', 'latency-receive-inflate', 'latency-ready-reconstruct',
        'latency-reconstruction', 'latency-draw', 'latency-viewport-draw',
        'latency-view-change-send',
    ].map(id => [id, { textContent: 'n/a' }]),
]);
globalThis.document = {
    getElementById: id => elements.get(id),
    createElement: () => ({
        width: 0, height: 0, tileCanvas: true,
        getContext: () => context,
    }),
};
globalThis.window = { addEventListener: (type, fn) => windowListeners.set(type, fn) };

const typeOf = sent => new Uint8Array(sent.buffer)[0];
const packetsOf = (socket, type) => socket.sent.filter(sent => typeOf(sent) === type);
const currentMetrics = () => Object.fromEntries([
    'latency-viewport-packet', 'latency-receive-inflate', 'latency-ready-reconstruct',
    'latency-reconstruction', 'latency-draw', 'latency-viewport-draw',
    'latency-view-change-send',
].map(id => [id, elements.get(id).textContent]));
const showMetrics = () => intervals[0]();
const fireTimer = (id, at) => {
    const timer = fakeTimers.get(id);
    assert.ok(timer, `expected pending timer ${id}`);
    fakeTimers.delete(id);
    now = at;
    timer.fn();
    return timer.delay;
};
const runFrame = () => {
    const callback = frames.shift();
    assert.ok(callback, 'expected a queued animation frame');
    callback();
};
const imagePacket = tile => {
    const packet = new Uint8Array(19);
    const dv = new DataView(packet.buffer);
    dv.setUint32(0, tile);
    packet[4] = 0; packet[5] = 0;
    packet[10] = 0; packet[11] = 1;
    dv.setUint32(12, 1); dv.setUint16(16, 1);
    packet[18] = 0;
    return packet;
};
const dataSegment = (seq, payload) => {
    const segment = new ArrayBuffer(16 + payload.length);
    const dv = new DataView(segment);
    const bytes = new Uint8Array(segment);
    bytes[0] = T.DATA;
    dv.setUint32(1, seq);
    dv.setUint16(14, payload.length);
    bytes.set(payload, 16);
    return segment;
};
const sendPacket = (socket, seq, tile) => socket.onmessage({
    data: dataSegment(seq, imagePacket(tile)),
});
const completeInflation = async inflateAt => {
    await new Promise(resolve => nativeSetTimeout(resolve, 0));
    const release = inflations.shift();
    assert.ok(release, 'expected a pending inflate');
    now = inflateAt;
    release();
    await new Promise(resolve => nativeSetTimeout(resolve, 20));
};
const lastViewport = socket => packetsOf(socket, T.VIEWPORT).at(-1);
const viewportBounds = sent => {
    const dv = new DataView(sent.buffer);
    return { x: dv.getUint32(1), y: dv.getUint32(5), width: dv.getUint32(9), height: dv.getUint32(13) };
};
const intersects = (tile, bounds) => {
    const x = (tile % 63) * 64, y = Math.floor(tile / 63) * 64;
    return x < bounds.x + bounds.width && x + 64 > bounds.x &&
        y < bounds.y + bounds.height && y + 64 > bounds.y;
};
const visibleCenterTile = bounds => {
    const tx = Math.floor((bounds.x + bounds.width / 2) / 64);
    const ty = Math.floor((bounds.y + bounds.height / 2) / 64);
    return ty * 63 + tx;
};

await import('../public/js/app.js?viewer_latency_test');
await new Promise(resolve => nativeSetTimeout(resolve, 0));
assert.equal(sockets.length, 1);
const firstSocket = sockets[0];
firstSocket.open();
assert.deepEqual([...new Uint8Array(firstSocket.sent[0].buffer)], [1, 1, 0]);
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, 0,
    'overview cap must suppress the initial whole-image tile request');
assert.equal(elements.get('latency-viewport-packet').textContent, 'n/a');
assert.equal(elements.get('latency-viewport-draw').textContent, 'n/a');
assert.equal(elements.get('latency-view-change-send').textContent, 'n/a',
    'initial-session VIEWPORT is excluded from view-change timing');

// Zoom past the overview cap. The cap-suppressed attempts must not create probes.
for (let i = 0; i < 10; i++) {
    now += 200;
    canvasListeners.get('wheel')({
        preventDefault() {}, clientX: 400, clientY: 300, deltaY: -1,
    });
}
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, 1,
    'a viewport is sent once its visible tile count falls below the cap');
const firstProbeSendAt = lastViewport(firstSocket).at;
showMetrics();
assert.match(elements.get('latency-view-change-send').textContent, /0\.0 ms · n=1/,
    'the immediate scheduled send is measured');
const firstBounds = viewportBounds(lastViewport(firstSocket));
const centerTile = visibleCenterTile(firstBounds);
assert.ok(intersects(centerTile, firstBounds));
assert.ok(!intersects(0, firstBounds), 'tile 0 should be outside the zoomed viewport');

// A tiny pan remains in the same tile bounds and must be deduplicated.
const viewportCount = packetsOf(firstSocket, T.VIEWPORT).length;
canvasListeners.get('mousedown')({ clientX: 400, clientY: 300 });
now += 200;
windowListeners.get('mousemove')({ clientX: 399, clientY: 300 });
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, viewportCount,
    'tile-bounds deduplication must suppress a sub-tile pan');
showMetrics();
assert.match(elements.get('latency-view-change-send').textContent, /n=1$/,
    'a deduplicated view change must not add a sample');

// An offscreen packet inflates but cannot satisfy the active viewport stages.
now = firstProbeSendAt + 10;
sendPacket(firstSocket, 0, 0);
assert.equal(packetsOf(firstSocket, T.ACK).length, 1);
const firstAck = new DataView(packetsOf(firstSocket, T.ACK)[0].buffer);
assert.equal(firstAck.getUint32(1), 1);
assert.equal(firstAck.getUint32(5), 256, 'legacy transport receive window is unchanged');
await completeInflation(firstProbeSendAt + 20);
showMetrics();
assert.equal(elements.get('latency-viewport-packet').textContent, 'n/a',
    'offscreen tile packet must not satisfy viewport-packet timing');
assert.equal(elements.get('latency-ready-reconstruct').textContent, 'n/a',
    'offscreen tile must not be reconstructed as a visible tile');
assert.equal(elements.get('latency-reconstruction').textContent, 'n/a');
assert.equal(elements.get('latency-viewport-draw').textContent, 'n/a');
assert.match(elements.get('latency-receive-inflate').textContent, /10\.0 ms · n=1/);

// A packet for the center tile matches; fake time gives each stage a known value.
const sendCountBeforeFrame = firstSocket.sent.length;
now = firstProbeSendAt + 40;
sendPacket(firstSocket, 1, centerTile);
assert.equal(packetsOf(firstSocket, T.ACK).length, 2);
await completeInflation(firstProbeSendAt + 60);
now = firstProbeSendAt + 70;
runFrame();
assert.equal(firstSocket.sent.length, sendCountBeforeFrame + 1,
    'drawing and diagnostic sampling must not add transport messages');
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, 1);

const tileDraws = renderEvents.filter(e => e.type === 'drawImage' && e.source?.tileCanvas);
assert.equal(tileDraws.length, 1, 'visible tile should be drawn once over the overview');
const imageDraws = renderEvents.filter(e => e.type === 'drawImage');
assert.equal(imageDraws.length, 2, 'overview and one reconstructed tile should be drawn');
assert.equal(imageDraws[0].source.overview, true, 'overview draw order is preserved');
assert.equal(imageDraws[1].args[0], 0, 'tile source crop starts at x=0');
assert.equal(imageDraws[1].args[1], 0, 'tile source crop starts at y=0');
assert.equal(imageDraws[1].args[2], 64, 'tile source crop width is preserved');
assert.equal(imageDraws[1].args[3], 64, 'tile source crop height is preserved');

showMetrics();
assert.match(elements.get('latency-viewport-packet').textContent, /40\.0 ms · n=1/);
assert.match(elements.get('latency-receive-inflate').textContent, /15\.0 ms · n=2/);
assert.match(elements.get('latency-ready-reconstruct').textContent, /10\.0 ms · n=1/);
assert.match(elements.get('latency-reconstruction').textContent, /0\.0 ms · n=1/);
assert.match(elements.get('latency-draw').textContent, /6\.0 ms · n=1/);
assert.match(elements.get('latency-viewport-draw').textContent, /76\.0 ms · n=1/);

// Repeated scheduled changes replace the pending timestamp. The final delayed
// send is 6 ms after the second schedule call, independent of the first call.
now = firstProbeSendAt + 220;
canvasListeners.get('wheel')({ preventDefault() {}, clientX: 400, clientY: 300, deltaY: -1 });
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, 1);
assert.equal(fakeTimers.size, 1);
assert.equal([...fakeTimers.values()][0].delay, 5);
now = firstProbeSendAt + 224;
canvasListeners.get('wheel')({ preventDefault() {}, clientX: 400, clientY: 300, deltaY: -1 });
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, 1);
assert.equal(fakeTimers.size, 1, 'a repeated change replaces the pending viewport timer');
assert.equal([...fakeTimers.values()][0].delay, 1);
const latestScheduleAt = now;
const pendingViewportTimer = fakeTimers.keys().next().value;
fireTimer(pendingViewportTimer, firstProbeSendAt + 230);
assert.equal(packetsOf(firstSocket, T.VIEWPORT).length, 2,
    'the delayed changed viewport should be sent once');
const secondProbeSendAt = lastViewport(firstSocket).at;
assert.equal(secondProbeSendAt - latestScheduleAt, 6);
showMetrics();
assert.match(elements.get('latency-view-change-send').textContent, /3\.0 ms · n=2/,
    'median of immediate 0 ms and latest-call delayed 6 ms proves the latest timestamp is used');
const secondBounds = viewportBounds(lastViewport(firstSocket));
assert.ok(intersects(centerTile, secondBounds), 'cached center tile stays visible');

// A new viewport that only redraws a cached tile cannot complete the new probe.
now += 10;
runFrame();
showMetrics();
assert.match(elements.get('latency-viewport-draw').textContent, /n=1$/,
    'cached redraw must not satisfy viewport-to-first-draw');

// Only a packet received after that send, then reconstructed and drawn visibly,
// completes the second viewport probe.
const secondPacketReceivedAt = secondProbeSendAt + 30;
now = secondPacketReceivedAt;
sendPacket(firstSocket, 2, centerTile);
await completeInflation(secondProbeSendAt + 50);
now = secondProbeSendAt + 60;
runFrame();
showMetrics();
assert.match(elements.get('latency-viewport-packet').textContent, /35\.0 ms · n=2/);
assert.match(elements.get('latency-ready-reconstruct').textContent, /10\.0 ms · n=2/);
assert.match(elements.get('latency-reconstruction').textContent, /0\.0 ms · n=2/);
assert.match(elements.get('latency-draw').textContent, /6\.0 ms · n=3/);
assert.match(elements.get('latency-viewport-draw').textContent, /71\.0 ms · n=2/);

// An inflate still pending during mode/session reset cannot publish stale samples.
now = secondProbeSendAt + 100;
sendPacket(firstSocket, 3, centerTile);
assert.equal(inflations.length, 1);
const mode = elements.get('mode');
mode.value = '1';
mode.listener();
assert.equal(firstSocket.readyState, MockWebSocket.CLOSED);
assert.equal(sockets.length, 2);
const resetSocket = sockets[1];
assert.deepEqual(currentMetrics(), Object.fromEntries([
    'latency-viewport-packet', 'latency-receive-inflate', 'latency-ready-reconstruct',
    'latency-reconstruction', 'latency-draw', 'latency-viewport-draw',
    'latency-view-change-send',
].map(id => [id, 'n/a'])));
resetSocket.open();
assert.deepEqual([...new Uint8Array(resetSocket.sent[0].buffer)], [1, 1, 1]);
const oldSendCount = firstSocket.sent.length;
await completeInflation(secondProbeSendAt + 150);
showMetrics();
assert.deepEqual(currentMetrics(), Object.fromEntries([
    'latency-viewport-packet', 'latency-receive-inflate', 'latency-ready-reconstruct',
    'latency-reconstruction', 'latency-draw', 'latency-viewport-draw',
    'latency-view-change-send',
].map(id => [id, 'n/a'])), 'stale decode cannot repopulate reset diagnostics');
assert.match(elements.get('stats').textContent, /paquetes: 0/);
assert.equal(firstSocket.sent.length, oldSendCount,
    'stale decode completion must not send transport messages');

// Pan-decay may send another VIEWPORT, but it is excluded from the user-change
// latency metric even when prediction decay changes the sent viewport key.
mode.value = '2';
mode.listener();
const predictiveSocket = sockets[2];
assert.deepEqual(currentMetrics(), Object.fromEntries([
    'latency-viewport-packet', 'latency-receive-inflate', 'latency-ready-reconstruct',
    'latency-reconstruction', 'latency-draw', 'latency-viewport-draw',
    'latency-view-change-send',
].map(id => [id, 'n/a'])));
predictiveSocket.open();
const predictionViewportCount = packetsOf(predictiveSocket, T.VIEWPORT).length;
now = secondProbeSendAt + 200;
canvasListeners.get('mousedown')({ clientX: 400, clientY: 300 });
now += 10;
windowListeners.get('mousemove')({ clientX: 399, clientY: 300 });
assert.equal(packetsOf(predictiveSocket, T.VIEWPORT).length, predictionViewportCount + 1,
    'the user pan sends a predictive viewport');
showMetrics();
assert.match(elements.get('latency-view-change-send').textContent, /0\.0 ms · n=1/);
const panDecayTimer = [...fakeTimers.entries()].find(([, timer]) => timer.delay === 500)?.[0];
assert.ok(panDecayTimer, 'pan should schedule confidence decay');
const sendsBeforeDecay = packetsOf(predictiveSocket, T.VIEWPORT).length;
fireTimer(panDecayTimer, now + 500);
assert.equal(packetsOf(predictiveSocket, T.VIEWPORT).length, sendsBeforeDecay + 1,
    'decayed prediction should send the changed viewport');
showMetrics();
assert.match(elements.get('latency-view-change-send').textContent, /0\.0 ms · n=1$/,
    'pan-decay send must not add a scheduled view-change sample');

console.log('OK viewer latency estimates, scheduled send timing, dedup/cap, render, transport, and reset');
