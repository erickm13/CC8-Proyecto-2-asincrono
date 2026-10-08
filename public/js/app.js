// Visor RAPID: conecta por WebSocket, recibe paquetes, reconstruye y pinta con
// carga progresiva y selectiva por viewport (zoom/pan).

import { T, parseImagePacket, encodeHello, encodeViewport, encodeForget } from './wire.js';
import { Receiver } from './receiver.js';
import { inflateRaw, reconstructComponent } from './decode.js';
import { startZipViewer } from './zip_viewer.js';

const canvas = document.getElementById('view');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const statsEl = document.getElementById('stats');
const latencyMetricEls = {
    viewportPacket: document.getElementById('latency-viewport-packet'),
    receiveInflate: document.getElementById('latency-receive-inflate'),
    readyReconstruct: document.getElementById('latency-ready-reconstruct'),
    reconstruction: document.getElementById('latency-reconstruction'),
    draw: document.getElementById('latency-draw'),
    viewportDraw: document.getElementById('latency-viewport-draw'),
    viewChangeSend: document.getElementById('latency-view-change-send'),
};
const modeEl = document.getElementById('mode');
const MODE_PREDICTIVE = 2;
let mode = Number(modeEl.value);

let header = null;
let ws = null;
let receiver = null;
let overviewBitmap = null;       // thumbnail de toda la imagen (capa base)
const REQUEST_TILES_CAP = 120;   // no pedir tiles si la vista abarca más que esto

// Caché de paquetes: tile -> [comp] -> Map(pkey -> precinct)
const tiles = new Map();
// Canvas reconstruido por tile: tile -> {canvas,x0,y0,vw,vh}
const tileCanvas = new Map();
const dirty = new Set();
const tileUsage = new Map();       // tile -> { bytes, utility }
let needsDraw = true;
let cacheBytes = 0;
let sessionGeneration = 0;
let appReady = false;
let frameStarted = false;
let statsTimer = null;
let panDecayTimer = null;

// Vista (coordenadas de imagen): screenX = (imgX - offsetX) * scale
const view = { scale: 1, offsetX: 0, offsetY: 0 };
let stats = { packets: 0, bytes: 0, evicted: 0, rwnd: 0 };

// Desalojo LRU: no guardar más de MAX_TILES tiles en caché del navegador.
const MAX_TILES = 160;
const MAX_CACHE_BYTES = 256 * 1024 * 1024;
const lastUsed = new Map();   // tile -> reloj lógico de último uso
let clock = 0;

// Control de flujo dinámico: rwnd (en segmentos) según el estado del cliente.
// The legacy scheduler keeps its original window; batch modes cap outstanding
// work at 16 DATA segments, each carrying at most 16 image packets.
const MIN_RWND_LEGACY = 8;
const MAX_RWND_LEGACY = 256;
const MIN_RWND_BATCH = 1;
const MAX_RWND_BATCH = 16;
let pendingDecodes = 0;       // paquetes esperando inflado/decodificación
const panHistory = [];

// Client-only performance.now() metrics. Viewport stages are approximate because
// DATA packets have no request id and may already be in flight when VIEWPORT is sent.
// viewportPacket: successful VIEWPORT send -> first later packet whose tile intersects
// that saved viewport; receiveInflate: onPacket entry -> inflateRaw completion;
// readyReconstruct: first visible inflate-ready time for a dirty tile -> reconstruction
// entry; reconstruction: reconstructTile entry -> completion; draw: draw() entry ->
// return; viewportDraw: successful VIEWPORT send -> completion of the first tile
// drawImage call for a tile reconstructed using a packet received after that send.
// viewChangeSend: latest scheduleViewport() call -> successful scheduled VIEWPORT send;
// initial-session and pan-decay sends are excluded. Each metric shows a rolling median
// of up to 64 samples and its count since reset.
const LATENCY_SAMPLE_WINDOW = 64;
const MAX_VIEWPORT_PROBES = 32;
const latencyMetrics = {
    viewportPacket: { samples: [], count: 0 },
    receiveInflate: { samples: [], count: 0 },
    readyReconstruct: { samples: [], count: 0 },
    reconstruction: { samples: [], count: 0 },
    draw: { samples: [], count: 0 },
    viewportDraw: { samples: [], count: 0 },
    viewChangeSend: { samples: [], count: 0 },
};
const viewportProbes = new Map();
const tileReadyViewportProbes = new Map();
const tileInflateReadyAt = new Map();
let nextViewportProbeId = 0;
// A newer scheduleViewport() call replaces the pending change timestamp.
let scheduledViewportChangeAt = null;

async function main() {
    header = await (await fetch('/api/manifest')).json();
    if (header.format === 'vips-zip') {
        await startZipViewer(header);
        return;
    }
    resizeCanvas();
    fitView();

    // Cargar el overview (thumbnail) para mostrar la imagen completa al instante.
    if (header.hasOverview) {
        try {
            const blob = await (await fetch('/api/overview')).blob();
            overviewBitmap = await createImageBitmap(blob);
        } catch (e) { /* sin overview: se verá solo lo que llegue por tiles */ }
    }

    appReady = true;
    initializeH2kControls();
    openSession();
    if (!frameStarted) { frameStarted = true; requestAnimationFrame(frame); }
    if (statsTimer === null) statsTimer = setInterval(updateStats, 500);
}

function initializeH2kControls() {
    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        const rect = canvas.getBoundingClientRect();
        const mx = e.clientX - rect.left, my = e.clientY - rect.top;
        const imgX = view.offsetX + mx / view.scale;
        const imgY = view.offsetY + my / view.scale;
        const factor = e.deltaY < 0 ? 1.2 : 1 / 1.2;
        view.scale = clamp(view.scale * factor, 0.02, 32);
        view.offsetX = imgX - mx / view.scale;
        view.offsetY = imgY - my / view.scale;
        scheduleViewport();
    }, { passive: false });

    let dragging = false, lastX = 0, lastY = 0;
    canvas.addEventListener('mousedown', (e) => { dragging = true; lastX = e.clientX; lastY = e.clientY; });
    window.addEventListener('mouseup', () => { dragging = false; });
    window.addEventListener('mousemove', (e) => {
        if (!dragging) return;
        const dx = -(e.clientX - lastX) / view.scale;
        const dy = -(e.clientY - lastY) / view.scale;
        view.offsetX += dx;
        view.offsetY += dy;
        panHistory.push({ dx, dy, at: performance.now() });
        if (panHistory.length > 8) panHistory.shift();
        schedulePanDecay();
        lastX = e.clientX; lastY = e.clientY;
        scheduleViewport();
    });
    window.addEventListener('resize', () => { resizeCanvas(); scheduleViewport(); });
    document.getElementById('fit').addEventListener('click', () => { fitView(); scheduleViewport(); });
    modeEl.addEventListener('change', () => resetForMode(Number(modeEl.value)));
}

function openSession() {
    scheduledViewportChangeAt = null;
    const generation = ++sessionGeneration;
    const socket = new WebSocket(`ws://${location.host}/stream`);
    ws = socket;
    socket.binaryType = 'arraybuffer';
    const sessionReceiver = new Receiver(buf => {
        if (socket.readyState === WebSocket.OPEN) socket.send(buf);
    }, payload => onPacket(payload, generation), mode === 0 ? MAX_RWND_LEGACY : MAX_RWND_BATCH);
    receiver = sessionReceiver;

    socket.onopen = () => {
        if (generation !== sessionGeneration) return;
        setStatus('conectado', true);
        socket.send(encodeHello(mode));
        sendViewport();
    };
    socket.onclose = () => { if (generation === sessionGeneration) setStatus('desconectado', false); };
    socket.onerror = () => { if (generation === sessionGeneration) setStatus('error de conexión', false); };
    socket.onmessage = (e) => {
        if (generation !== sessionGeneration) return;
        const type = new Uint8Array(e.data, 0, 1)[0];
        if (type === T.DATA) {
            try { sessionReceiver.onData(e.data); }
            catch (err) { setStatus('datos inválidos', false); socket.close(); }
        }
    };
}

function resetForMode(nextMode) {
    if (nextMode === mode) return;
    mode = nextMode;
    sessionGeneration++;
    const previous = ws;
    ws = null;
    receiver = null;
    if (previous && previous.readyState < WebSocket.CLOSING) previous.close();
    clearTimeout(vpTimer);
    vpTimer = null;
    clearTimeout(panDecayTimer);
    panDecayTimer = null;
    lastViewportAt = 0;
    lastViewportKey = null;
    tiles.clear();
    tileCanvas.clear();
    dirty.clear();
    tileUsage.clear();
    lastUsed.clear();
    cacheBytes = 0;
    clock = 0;
    pendingDecodes = 0;
    resetLatencyMetrics();
    stats = { packets: 0, bytes: 0, evicted: 0, rwnd: 0 };
    panHistory.length = 0;
    needsDraw = true;
    statsEl.textContent = 'esperando datos…';
    if (appReady) {
        setStatus('conectando', false);
        openSession();
    }
    updateStats();
}

function resetLatencyMetrics() {
    for (const metric of Object.values(latencyMetrics)) {
        metric.samples.length = 0;
        metric.count = 0;
    }
    viewportProbes.clear();
    tileReadyViewportProbes.clear();
    tileInflateReadyAt.clear();
    nextViewportProbeId = 0;
    scheduledViewportChangeAt = null;
    renderLatencyMetrics();
}

function addLatencySample(name, duration) {
    if (!Number.isFinite(duration) || duration < 0) return;
    const metric = latencyMetrics[name];
    metric.samples.push(duration);
    if (metric.samples.length > LATENCY_SAMPLE_WINDOW) metric.samples.shift();
    metric.count++;
}

function formatLatency(metric) {
    if (!metric.count) return 'n/a';
    const sorted = [...metric.samples].sort((a, b) => a - b);
    const middle = sorted.length >> 1;
    const median = sorted.length % 2
        ? sorted[middle]
        : (sorted[middle - 1] + sorted[middle]) / 2;
    return `${median.toFixed(1)} ms · n=${metric.count}`;
}

function renderLatencyMetrics() {
    for (const [name, el] of Object.entries(latencyMetricEls)) {
        if (el) el.textContent = formatLatency(latencyMetrics[name]);
    }
}

function removeViewportProbe(id) {
    viewportProbes.delete(id);
    for (const [tile, ids] of tileReadyViewportProbes) {
        ids.delete(id);
        if (!ids.size) tileReadyViewportProbes.delete(tile);
    }
}

function addViewportProbe(bounds, sentAt) {
    const id = ++nextViewportProbeId;
    viewportProbes.set(id, { id, sentAt, bounds, packetSeen: false, drawSeen: false });
    // Drop the oldest unfinished attribution snapshots if motion outpaces responses.
    while (viewportProbes.size > MAX_VIEWPORT_PROBES) {
        removeViewportProbe(viewportProbes.keys().next().value);
    }
}

function tileIntersectsViewport(tile, bounds) {
    if (bounds.width <= 0 || bounds.height <= 0) return false;
    const ts = header.tileSize;
    const x = (tile % header.tilesX) * ts;
    const y = Math.floor(tile / header.tilesX) * ts;
    const width = Math.min(ts, header.width - x);
    const height = Math.min(ts, header.height - y);
    return x < bounds.x + bounds.width && x + width > bounds.x &&
        y < bounds.y + bounds.height && y + height > bounds.y;
}

function tileIntersectsCurrentView(tile) {
    return tileIntersectsViewport(tile, {
        x: view.offsetX,
        y: view.offsetY,
        width: canvas.width / view.scale,
        height: canvas.height / view.scale,
    });
}

function noteViewportPacket(tile, receivedAt) {
    let ids = null;
    for (const probe of viewportProbes.values()) {
        if (receivedAt < probe.sentAt || !tileIntersectsViewport(tile, probe.bounds)) continue;
        if (!probe.packetSeen) {
            probe.packetSeen = true;
            addLatencySample('viewportPacket', receivedAt - probe.sentAt);
        }
        if (!probe.drawSeen) (ids ||= []).push(probe.id);
    }
    return ids;
}

function getTile(tile) {
    if (!tiles.has(tile)) {
        const comps = [];
        for (let c = 0; c < header.components; c++) comps.push(new Map());
        tiles.set(tile, comps);
        tileUsage.set(tile, { bytes: 0, utility: 0 });
    }
    return tiles.get(tile);
}

async function onPacket(payload, generation) {
    if (generation !== sessionGeneration) return;
    const receivedAt = performance.now();
    let pk;
    try { pk = parseImagePacket(payload); }
    catch (e) { if (generation === sessionGeneration) setStatus('paquete inválido', false); return; }
    if (pk.tile >= header.tilesX * header.tilesY || pk.comp >= header.components ||
        !pk.numPlanes || pk.layer >= pk.numPlanes) {
        setStatus('paquete inválido', false);
        return;
    }
    const viewportProbeIds = noteViewportPacket(pk.tile, receivedAt);
    const tileEntry = getTile(pk.tile);
    const map = tileEntry[pk.comp];
    const key = `${pk.level}_${pk.py}_${pk.px}`;
    let pr = map.get(key);
    if (!pr) {
        pr = { level: pk.level, py: pk.py, px: pk.px, numCoeffs: pk.numCoeffs,
               numPlanes: pk.numPlanes, layers: new Array(pk.numPlanes),
               layerUtility: new Array(pk.numPlanes).fill(0), received: 0 };
        map.set(key, pr);
    }
    stats.packets++;
    stats.bytes += pk.data.length;
    lastUsed.set(pk.tile, ++clock);
    pendingDecodes++;
    try {
        const inflated = await inflateRaw(pk.data);   // DEFLATE nativo del navegador
        const inflateReadyAt = performance.now();
        if (generation === sessionGeneration) addLatencySample('receiveInflate', inflateReadyAt - receivedAt);
        if (generation !== sessionGeneration || tiles.get(pk.tile) !== tileEntry) return;
        const oldLayer = pr.layers[pk.layer];
        const oldSize = oldLayer ? oldLayer.byteLength : 0;
        const oldUtility = pr.layerUtility[pk.layer] || 0;
        const utility = packetUtility(pk);
        pr.layers[pk.layer] = inflated;
        pr.layerUtility[pk.layer] = utility;
        const usage = tileUsage.get(pk.tile);
        usage.bytes += inflated.byteLength - oldSize;
        usage.utility += utility - oldUtility;
        cacheBytes += inflated.byteLength - oldSize;
        let r = 0;
        while (r < pr.numPlanes && pr.layers[r]) r++;
        pr.received = r;
        dirty.add(pk.tile);
        if (tileIntersectsCurrentView(pk.tile) && !tileInflateReadyAt.has(pk.tile)) {
            tileInflateReadyAt.set(pk.tile, inflateReadyAt);
        }
        if (viewportProbeIds) {
            let readyIds = tileReadyViewportProbes.get(pk.tile);
            if (!readyIds) tileReadyViewportProbes.set(pk.tile, readyIds = new Set());
            for (const id of viewportProbeIds) if (viewportProbes.has(id)) readyIds.add(id);
        }
        if (mode === MODE_PREDICTIVE) evict();
    } catch (e) {
        if (generation === sessionGeneration) setStatus('error al decodificar', false);
    } finally {
        if (generation === sessionGeneration) pendingDecodes--;
    }
}

function packetUtility(pk) {
    const plane = Math.max(0, pk.numPlanes - 1 - pk.layer);
    return pk.numCoeffs * (2 ** Math.min(80, 2 * plane)) / Math.max(1, pk.data.length);
}

function adjustTileBytes(tile, delta) {
    const usage = tileUsage.get(tile);
    if (usage) { usage.bytes += delta; cacheBytes += delta; }
}

/**
 * Ajusta rwnd (flow control) al estado del cliente: la ventana se encoge si la
 * caché está casi llena o si hay mucho backlog de decodificación, de modo que el
 * servidor reduce el ritmo y no satura el navegador; se recupera al liberarse.
 */
function updateRwnd() {
    if (!receiver) return;
    const minRwnd = mode === 0 ? MIN_RWND_LEGACY : MIN_RWND_BATCH;
    const maxRwnd = mode === 0 ? MAX_RWND_LEGACY : MAX_RWND_BATCH;
    const cacheHeadroom = mode === MODE_PREDICTIVE
        ? clamp((MAX_CACHE_BYTES - cacheBytes) / MAX_CACHE_BYTES, 0, 1)
        : clamp((MAX_TILES - tiles.size) / MAX_TILES, 0, 1);
    const backlogPenalty = clamp(pendingDecodes / 64, 0, 1);
    const factor = cacheHeadroom * (1 - backlogPenalty);
    const rwnd = Math.round(minRwnd + factor * (maxRwnd - minRwnd));
    receiver.rwnd = Math.max(minRwnd, Math.min(maxRwnd, rwnd));
    stats.rwnd = receiver.rwnd;
}

// ---- Desalojo LRU (libera memoria y avisa al servidor con FORGET) ----------

function visibleTiles() {
    const ts = header.tileSize;
    const vx = Math.max(0, Math.floor(view.offsetX));
    const vy = Math.max(0, Math.floor(view.offsetY));
    const vw = Math.ceil(canvas.width / view.scale);
    const vh = Math.ceil(canvas.height / view.scale);
    const txMin = Math.max(0, Math.floor(vx / ts));
    const tyMin = Math.max(0, Math.floor(vy / ts));
    const txMax = Math.min(header.tilesX - 1, Math.floor((vx + vw) / ts));
    const tyMax = Math.min(header.tilesY - 1, Math.floor((vy + vh) / ts));
    const set = new Set();
    for (let ty = tyMin; ty <= tyMax; ty++)
        for (let tx = txMin; tx <= txMax; tx++) set.add(ty * header.tilesX + tx);
    return set;
}

function evict() {
    if (mode === MODE_PREDICTIVE ? cacheBytes <= MAX_CACHE_BYTES : tiles.size <= MAX_TILES) return;
    const visible = visibleTiles();
    let candidates;
    if (mode === MODE_PREDICTIVE) {
        const byValue = (a, b) => {
            const ua = tileUsage.get(a), ub = tileUsage.get(b);
            const score = u => u.utility / Math.max(1, u.bytes);
            return score(ua) - score(ub) || (lastUsed.get(a) || 0) - (lastUsed.get(b) || 0);
        };
        candidates = [
            ...[...tiles.keys()].filter(t => !visible.has(t)).sort(byValue),
            ...[...tiles.keys()].filter(t => visible.has(t)).sort(byValue),
        ];
    } else {
        for (const t of visible) lastUsed.set(t, ++clock); // los visibles no se desalojan
        candidates = [...tiles.keys()]
            .filter(t => !visible.has(t))
            .sort((a, b) => (lastUsed.get(a) || 0) - (lastUsed.get(b) || 0));
    }
    const forgotten = [];
    while (candidates.length && (mode === MODE_PREDICTIVE ? cacheBytes > MAX_CACHE_BYTES : tiles.size > MAX_TILES)) {
        const t = candidates.shift();
        tiles.delete(t);
        tileCanvas.delete(t);
        dirty.delete(t);
        tileInflateReadyAt.delete(t);
        tileReadyViewportProbes.delete(t);
        lastUsed.delete(t);
        const usage = tileUsage.get(t);
        if (usage) cacheBytes -= usage.bytes;
        tileUsage.delete(t);
        forgotten.push(t);
    }
    if (forgotten.length) {
        stats.evicted += forgotten.length;
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(encodeForget(forgotten));
    }
}

// ---- Reconstrucción y render ----------------------------------------------

function reconstructTile(tile) {
    const reconstructionStartedAt = performance.now();
    const inflateReadyAt = tileInflateReadyAt.get(tile);
    if (inflateReadyAt !== undefined && tileIntersectsCurrentView(tile)) {
        addLatencySample('readyReconstruct', reconstructionStartedAt - inflateReadyAt);
    }
    tileInflateReadyAt.delete(tile);
    const ts = header.tileSize;
    const tx = tile % header.tilesX, ty = (tile / header.tilesX) | 0;
    const x0 = tx * ts, y0 = ty * ts;
    const vw = Math.min(ts, header.width - x0), vh = Math.min(ts, header.height - y0);
    const comps = getTile(tile);

    const rec = [];
    for (let c = 0; c < header.components; c++) {
        rec[c] = reconstructComponent(header, [...comps[c].values()]);
    }

    const off = document.createElement('canvas');
    off.width = vw; off.height = vh;
    const octx = off.getContext('2d');
    const img = octx.createImageData(vw, vh);
    const viewportProbeIds = [...(tileReadyViewportProbes.get(tile) || [])]
        .filter(id => viewportProbes.has(id));
    for (let yy = 0; yy < vh; yy++) {
        for (let xx = 0; xx < vw; xx++) {
            const p = yy * ts + xx;
            const o = (yy * vw + xx) * 4;
            if (header.components === 1) {
                const v = rec[0][p];
                img.data[o] = v; img.data[o + 1] = v; img.data[o + 2] = v;
            } else {
                img.data[o] = rec[0][p]; img.data[o + 1] = rec[1][p]; img.data[o + 2] = rec[2][p];
            }
            img.data[o + 3] = 255;
        }
    }
    octx.putImageData(img, 0, 0);
    const previous = tileCanvas.get(tile);
    if (previous) adjustTileBytes(tile, -previous.vw * previous.vh * 4);
    tileCanvas.set(tile, { canvas: off, x0, y0, vw, vh, viewportProbeIds });
    tileReadyViewportProbes.delete(tile);
    adjustTileBytes(tile, vw * vh * 4);
    addLatencySample('reconstruction', performance.now() - reconstructionStartedAt);
}

function frame() {
    // Reconstruye a lo sumo unos pocos tiles sucios por cuadro (fluidez).
    let budget = 6;
    const ts = header.tileSize;
    const left = view.offsetX, top = view.offsetY;
    const right = left + canvas.width / view.scale;
    const bottom = top + canvas.height / view.scale;
    for (const t of dirty) {
        if (!tiles.has(t)) {
            dirty.delete(t);
            continue;
        }
        const x = (t % header.tilesX) * ts;
        const y = Math.floor(t / header.tilesX) * ts;
        if (x >= right || y >= bottom || x + ts <= left || y + ts <= top) continue;
        reconstructTile(t);
        dirty.delete(t);
        needsDraw = true;
        if (--budget <= 0) break;
    }
    if (needsDraw) {
        draw();
        needsDraw = false;
    }
    evict();
    updateRwnd();
    requestAnimationFrame(frame);
}

function draw() {
    const drawStartedAt = performance.now();
    try {
        ctx.fillStyle = '#0b0e1a';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        const left = view.offsetX;
        const top = view.offsetY;
        const right = left + canvas.width / view.scale;
        const bottom = top + canvas.height / view.scale;

        // Capa base: el overview estirado sobre toda la imagen (aparece al instante).
        if (overviewBitmap) {
            const x0 = Math.max(0, left), y0 = Math.max(0, top);
            const x1 = Math.min(header.width, right), y1 = Math.min(header.height, bottom);
            if (x1 > x0 && y1 > y0) {
                const sx = overviewBitmap.width / header.width;
                const sy = overviewBitmap.height / header.height;
                ctx.imageSmoothingEnabled = true;
                ctx.drawImage(overviewBitmap, x0 * sx, y0 * sy, (x1 - x0) * sx, (y1 - y0) * sy,
                    (x0 - left) * view.scale, (y0 - top) * view.scale,
                    (x1 - x0) * view.scale, (y1 - y0) * view.scale);
            }
        }

        // Encima, los tiles ya recibidos (nítidos) donde los haya.
        ctx.imageSmoothingEnabled = false;
        for (const [tile, entry] of tileCanvas) {
            const { canvas: c, x0, y0, vw, vh } = entry;
            const sx = Math.max(0, left - x0), sy = Math.max(0, top - y0);
            const ex = Math.min(vw, right - x0), ey = Math.min(vh, bottom - y0);
            if (ex <= sx || ey <= sy) continue;
            ctx.drawImage(c, sx, sy, ex - sx, ey - sy,
                (x0 + sx - left) * view.scale, (y0 + sy - top) * view.scale,
                (ex - sx) * view.scale, (ey - sy) * view.scale);
            const tileDrawnAt = performance.now();
            for (const id of entry.viewportProbeIds) {
                const probe = viewportProbes.get(id);
                if (!probe || probe.drawSeen || tileDrawnAt < probe.sentAt ||
                    !tileIntersectsViewport(tile, probe.bounds)) continue;
                probe.drawSeen = true;
                addLatencySample('viewportDraw', tileDrawnAt - probe.sentAt);
                removeViewportProbe(id);
            }
            entry.viewportProbeIds = [];
        }
    } finally {
        addLatencySample('draw', performance.now() - drawStartedAt);
    }
}

// ---- Vista, zoom y pan -----------------------------------------------------

function resizeCanvas() {
    canvas.width = canvas.clientWidth;
    canvas.height = canvas.clientHeight;
}

function fitView() {
    view.scale = Math.min(canvas.width / header.width, canvas.height / header.height);
    view.offsetX = header.width / 2 - canvas.width / (2 * view.scale);
    view.offsetY = header.height / 2 - canvas.height / (2 * view.scale);
}

let vpTimer = null;
let lastViewportAt = 0;
let lastViewportKey = null;
function sendViewport(fromScheduledViewChange = false) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const vx = Math.max(0, Math.floor(view.offsetX));
    const vy = Math.max(0, Math.floor(view.offsetY));
    const vw = Math.min(header.width - vx, Math.ceil(canvas.width / view.scale));
    const vh = Math.min(header.height - vy, Math.ceil(canvas.height / view.scale));
    // Si hay overview y la vista abarca demasiados tiles (muy alejado), basta el
    // overview: no pedimos tiles. Sin overview, dejamos pedir (el servidor topa
    // la enumeración) para no quedarnos en blanco.
    const ts = header.tileSize;
    const ntx = Math.floor((vx + vw - 1) / ts) - Math.floor(vx / ts) + 1;
    const nty = Math.floor((vy + vh - 1) / ts) - Math.floor(vy / ts) + 1;
    if (overviewBitmap && ntx * nty > REQUEST_TILES_CAP) {
        lastViewportKey = null;
        scheduledViewportChangeAt = null;
        return;
    }

    // Nivel de resolución necesario según el zoom (no pedir detalle innecesario).
    const maxLevel = clamp(Math.round(header.levels + Math.log2(view.scale)) + 1, 0, header.levels);
    const prediction = mode === MODE_PREDICTIVE ? predictPan() : null;
    const key = `${Math.floor(vx / ts)}:${Math.floor(vy / ts)}:` +
        `${Math.floor((vx + vw - 1) / ts)}:${Math.floor((vy + vh - 1) / ts)}:${maxLevel}`;
    const motionKey = prediction ? `:${Math.round(prediction.dx / 8)}:${Math.round(prediction.dy / 8)}:${Math.floor(prediction.confidence / 32)}` : '';
    if (key + motionKey === lastViewportKey) {
        scheduledViewportChangeAt = null;
        return;
    }
    const sentWidth = Math.max(1, vw), sentHeight = Math.max(1, vh);
    try {
        ws.send(encodeViewport(vx, vy, sentWidth, sentHeight, maxLevel, prediction));
    } catch (err) {
        scheduledViewportChangeAt = null;
        throw err;
    }
    const sentAt = performance.now();
    addViewportProbe({ x: vx, y: vy, width: sentWidth, height: sentHeight }, sentAt);
    if (fromScheduledViewChange && scheduledViewportChangeAt !== null) {
        addLatencySample('viewChangeSend', sentAt - scheduledViewportChangeAt);
    }
    scheduledViewportChangeAt = null;
    lastViewportKey = key + motionKey;
}

function predictPan() {
    const now = performance.now();
    const recent = panHistory.filter(p => now - p.at <= 1500);
    if (!recent.length) return { dx: 0, dy: 0, confidence: 0 };
    let dx = 0, dy = 0;
    for (const p of recent) {
        const weight = Math.exp(-(now - p.at) / 600);
        dx += p.dx * weight;
        dy += p.dy * weight;
    }
    const scale = Math.max(Math.abs(dx), Math.abs(dy), 1);
    const age = now - recent[recent.length - 1].at;
    const confidence = 255 * Math.exp(-age / 1200) * Math.min(1, recent.length / 3);
    return { dx: 127 * dx / scale, dy: 127 * dy / scale, confidence };
}

function schedulePanDecay() {
    clearTimeout(panDecayTimer);
    panDecayTimer = null;
    if (mode !== MODE_PREDICTIVE || !panHistory.length) return;
    panDecayTimer = setTimeout(() => {
        panDecayTimer = null;
        if (performance.now() - panHistory[panHistory.length - 1].at >= 1500) panHistory.length = 0;
        sendViewport();
        if (panHistory.length) schedulePanDecay();
    }, 500);
}

function scheduleViewport() {
    const now = performance.now();
    scheduledViewportChangeAt = now;
    needsDraw = true;
    clearTimeout(vpTimer);
    if (now - lastViewportAt >= 100) {
        lastViewportAt = now;
        sendViewport(true);
    } else {
        vpTimer = setTimeout(() => {
            lastViewportAt = performance.now();
            sendViewport(true);
        }, 25 - (now - lastViewportAt));
    }
}

// ---- UI auxiliar -----------------------------------------------------------

function updateStats() {
    renderLatencyMetrics();
    if (!receiver) return;
    const cache = mode === MODE_PREDICTIVE
        ? `${(cacheBytes / (1024 * 1024)).toFixed(1)}/256 MiB`
        : `${tiles.size}/${MAX_TILES} tiles`;
    statsEl.textContent =
        `paquetes: ${stats.packets} · datos: ${(stats.bytes / 1024).toFixed(1)} KB · ` +
        `entregados: ${receiver.delivered} · fuera de orden: ${receiver.ooo.size} · ` +
        `caché: ${cache} · desalojados: ${stats.evicted} · ` +
        `rwnd: ${stats.rwnd} · zoom: ${view.scale.toFixed(2)}×`;
}

function setStatus(text, ok) {
    statusEl.textContent = text;
    statusEl.className = ok ? 'ok' : 'err';
}

function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

main();
