// Visor RAPID: conecta por WebSocket, recibe paquetes, reconstruye y pinta con
// carga progresiva y selectiva por viewport (zoom/pan).

import { T, parseImagePacket, encodeViewport, encodeForget } from './wire.js';
import { Receiver } from './receiver.js';
import { inflateRaw, reconstructComponent } from './decode.js';

const canvas = document.getElementById('view');
const ctx = canvas.getContext('2d');
const statusEl = document.getElementById('status');
const statsEl = document.getElementById('stats');

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

// Vista (coordenadas de imagen): screenX = (imgX - offsetX) * scale
const view = { scale: 1, offsetX: 0, offsetY: 0 };
let stats = { packets: 0, bytes: 0, evicted: 0, rwnd: 0 };

// Desalojo LRU: no guardar más de MAX_TILES tiles en caché del navegador.
const MAX_TILES = 160;
const lastUsed = new Map();   // tile -> reloj lógico de último uso
let clock = 0;

// Control de flujo dinámico: rwnd (en segmentos) según el estado del cliente.
const MIN_RWND = 8;
const MAX_RWND = 256;
let pendingDecodes = 0;       // paquetes esperando inflado/decodificación

async function main() {
    header = await (await fetch('/api/manifest')).json();
    resizeCanvas();
    fitView();

    // Cargar el overview (thumbnail) para mostrar la imagen completa al instante.
    if (header.hasOverview) {
        try {
            const blob = await (await fetch('/api/overview')).blob();
            overviewBitmap = await createImageBitmap(blob);
        } catch (e) { /* sin overview: se verá solo lo que llegue por tiles */ }
    }

    ws = new WebSocket(`ws://${location.host}/stream`);
    ws.binaryType = 'arraybuffer';
    receiver = new Receiver(buf => ws.send(buf), onPacket, MAX_RWND);

    ws.onopen = () => { setStatus('conectado', true); sendViewport(); };
    ws.onclose = () => setStatus('desconectado', false);
    ws.onerror = () => setStatus('error de conexión', false);
    ws.onmessage = (e) => {
        const type = new Uint8Array(e.data, 0, 1)[0];
        if (type === T.DATA) receiver.onData(e.data);
    };

    requestAnimationFrame(frame);
    setInterval(updateStats, 500);
}

function getTile(tile) {
    if (!tiles.has(tile)) {
        const comps = [];
        for (let c = 0; c < header.components; c++) comps.push(new Map());
        tiles.set(tile, comps);
    }
    return tiles.get(tile);
}

async function onPacket(payload) {
    const pk = parseImagePacket(payload);
    const map = getTile(pk.tile)[pk.comp];
    const key = `${pk.level}_${pk.py}_${pk.px}`;
    let pr = map.get(key);
    if (!pr) {
        pr = { level: pk.level, py: pk.py, px: pk.px, numCoeffs: pk.numCoeffs,
               numPlanes: pk.numPlanes, layers: new Array(pk.numPlanes), received: 0 };
        map.set(key, pr);
    }
    stats.packets++;
    stats.bytes += pk.data.length;
    lastUsed.set(pk.tile, ++clock);
    pendingDecodes++;
    try {
        const inflated = await inflateRaw(pk.data);   // DEFLATE nativo del navegador
        pr.layers[pk.layer] = inflated;
        let r = 0;
        while (r < pr.numPlanes && pr.layers[r]) r++;
        pr.received = r;
        dirty.add(pk.tile);
    } finally {
        pendingDecodes--;
    }
}

/**
 * Ajusta rwnd (flow control) al estado del cliente: la ventana se encoge si la
 * caché está casi llena o si hay mucho backlog de decodificación, de modo que el
 * servidor reduce el ritmo y no satura el navegador; se recupera al liberarse.
 */
function updateRwnd() {
    if (!receiver) return;
    const cacheHeadroom = clamp((MAX_TILES - tiles.size) / MAX_TILES, 0, 1);
    const backlogPenalty = clamp(pendingDecodes / 64, 0, 1);
    const factor = cacheHeadroom * (1 - backlogPenalty);
    const rwnd = Math.round(MIN_RWND + factor * (MAX_RWND - MIN_RWND));
    receiver.rwnd = Math.max(MIN_RWND, rwnd);
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
    if (tiles.size <= MAX_TILES) return;
    const visible = visibleTiles();
    for (const t of visible) lastUsed.set(t, ++clock); // los visibles no se desalojan
    const candidates = [...tiles.keys()]
        .filter(t => !visible.has(t))
        .sort((a, b) => (lastUsed.get(a) || 0) - (lastUsed.get(b) || 0));
    const forgotten = [];
    while (tiles.size > MAX_TILES && candidates.length) {
        const t = candidates.shift();
        tiles.delete(t);
        tileCanvas.delete(t);
        lastUsed.delete(t);
        forgotten.push(t);
    }
    if (forgotten.length) {
        stats.evicted += forgotten.length;
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(encodeForget(forgotten));
    }
}

// ---- Reconstrucción y render ----------------------------------------------

function reconstructTile(tile) {
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
    tileCanvas.set(tile, { canvas: off, x0, y0, vw, vh });
}

function frame() {
    // Reconstruye a lo sumo unos pocos tiles sucios por cuadro (fluidez).
    let budget = 6;
    for (const t of dirty) {
        reconstructTile(t);
        dirty.delete(t);
        if (--budget <= 0) break;
    }
    draw();
    evict();
    updateRwnd();
    requestAnimationFrame(frame);
}

function draw() {
    ctx.fillStyle = '#0b0e1a';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // Capa base: el overview estirado sobre toda la imagen (aparece al instante).
    if (overviewBitmap) {
        const dx = (0 - view.offsetX) * view.scale;
        const dy = (0 - view.offsetY) * view.scale;
        ctx.imageSmoothingEnabled = true;
        ctx.drawImage(overviewBitmap, dx, dy, header.width * view.scale, header.height * view.scale);
    }

    // Encima, los tiles ya recibidos (nítidos) donde los haya.
    ctx.imageSmoothingEnabled = false;
    for (const { canvas: c, x0, y0, vw, vh } of tileCanvas.values()) {
        const dx = (x0 - view.offsetX) * view.scale;
        const dy = (y0 - view.offsetY) * view.scale;
        const dw = vw * view.scale, dh = vh * view.scale;
        if (dx + dw < 0 || dy + dh < 0 || dx > canvas.width || dy > canvas.height) continue;
        ctx.drawImage(c, dx, dy, dw, dh);
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
function sendViewport() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const vx = Math.max(0, Math.floor(view.offsetX));
    const vy = Math.max(0, Math.floor(view.offsetY));
    const vw = Math.min(header.width - vx, Math.ceil(canvas.width / view.scale));
    const vh = Math.min(header.height - vy, Math.ceil(canvas.height / view.scale));
    // Si la vista abarca demasiados tiles (muy alejado), basta el overview: no
    // pedimos tiles para no enumerar/transferir decenas de miles.
    const ts = header.tileSize;
    const ntx = Math.floor((vx + vw - 1) / ts) - Math.floor(vx / ts) + 1;
    const nty = Math.floor((vy + vh - 1) / ts) - Math.floor(vy / ts) + 1;
    if (ntx * nty > REQUEST_TILES_CAP) return;

    // Nivel de resolución necesario según el zoom (no pedir detalle innecesario).
    const maxLevel = clamp(Math.round(header.levels + Math.log2(view.scale)) + 1, 0, header.levels);
    ws.send(encodeViewport(vx, vy, Math.max(1, vw), Math.max(1, vh), maxLevel));
}

function scheduleViewport() {
    clearTimeout(vpTimer);
    vpTimer = setTimeout(sendViewport, 150);
}

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
    view.offsetX -= (e.clientX - lastX) / view.scale;
    view.offsetY -= (e.clientY - lastY) / view.scale;
    lastX = e.clientX; lastY = e.clientY;
    scheduleViewport();
});
window.addEventListener('resize', () => { resizeCanvas(); scheduleViewport(); });

document.getElementById('fit').addEventListener('click', () => { fitView(); scheduleViewport(); });

// ---- UI auxiliar -----------------------------------------------------------

function updateStats() {
    if (!receiver) return;
    statsEl.textContent =
        `paquetes: ${stats.packets} · datos: ${(stats.bytes / 1024).toFixed(1)} KB · ` +
        `entregados: ${receiver.delivered} · fuera de orden: ${receiver.ooo.size} · ` +
        `caché: ${tiles.size}/${MAX_TILES} tiles · desalojados: ${stats.evicted} · ` +
        `rwnd: ${stats.rwnd} · zoom: ${view.scale.toFixed(2)}×`;
}

function setStatus(text, ok) {
    statusEl.textContent = text;
    statusEl.className = ok ? 'ok' : 'err';
}

function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

main();
