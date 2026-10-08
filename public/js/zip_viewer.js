import { T, encodeHello, encodeViewport, encodeForget } from './wire.js';
import { Receiver } from './receiver.js';

const MAX_FRAGMENT_BYTES = 16000;
const MAX_TILE_BYTES = 32 * 1024 * 1024;
const MAX_CACHE_BYTES = 256 * 1024 * 1024;
const MAX_TILES = 160;
const MAX_REQUEST_TILES = 120;
const MAX_RWND_BATCH = 16;
const MIN_RWND_BATCH = 1;
const MAX_RWND_LEGACY = 256;
const MIN_RWND_LEGACY = 8;

export async function startZipViewer(header) {
    if (header.format !== 'vips-zip' || !Number.isSafeInteger(header.width) ||
        !Number.isSafeInteger(header.height) || header.width < 1 || header.height < 1 ||
        !Number.isInteger(header.tileSize) || header.tileSize < 1 ||
        !Number.isInteger(header.levels) || header.levels < 0 || header.levels > 30) {
        throw new Error('manifiesto vips-zip inválido');
    }

    const canvas = document.getElementById('view');
    const ctx = canvas.getContext('2d');
    const status = document.getElementById('status');
    const statsEl = document.getElementById('stats');
    const modeEl = document.getElementById('mode');
    const latencyPanel = document.getElementById('latency');
    if (latencyPanel) latencyPanel.hidden = true;

    const geometries = Array.from({ length: header.levels + 1 }, (_, level) => {
        const divisor = 2 ** (header.levels - level);
        const width = Math.ceil(header.width / divisor);
        const height = Math.ceil(header.height / divisor);
        return {
            width,
            height,
            tilesX: Math.ceil(width / header.tileSize),
            tilesY: Math.ceil(height / header.tileSize),
            sourceScale: divisor,
        };
    });
    const tileStarts = [];
    let nextTileId = 0;
    for (const geometry of geometries) {
        tileStarts.push(nextTileId);
        nextTileId += geometry.tilesX * geometry.tilesY;
    }

    const tiles = new Map();
    const view = { scale: 1, offsetX: 0, offsetY: 0 };
    let overviewBitmap = null;
    let ws = null;
    let receiver = null;
    let mode = Number(modeEl.value);
    let generation = 0;
    let cacheBytes = 0;
    let pendingDecodes = 0;
    let clock = 0;
    let needsDraw = true;
    let frameStarted = false;
    let statsTimer = null;
    let viewportTimer = null;
    let panDecayTimer = null;
    let lastViewportAt = 0;
    let lastViewportKey = null;
    const panHistory = [];
    const stats = { packets: 0, bytes: 0, evicted: 0, duplicates: 0, rwnd: 0 };

    function validateZipPacket(packet) { parseZipPacket(packet); }

    function parseZipPacket(packet) {
        if (!(packet instanceof Uint8Array) || packet.byteLength < 22 ||
            packet[0] !== 0x5a || packet[1] !== 0x54 || packet[2] !== 1) {
            throw new RangeError('paquete ZT inválido');
        }
        const dv = new DataView(packet.buffer, packet.byteOffset, packet.byteLength);
        const tileId = dv.getUint32(3);
        const level = packet[7];
        const tx = dv.getUint16(8), ty = dv.getUint16(10);
        const part = dv.getUint16(12), parts = dv.getUint16(14);
        const totalBytes = dv.getUint32(16), dataLength = dv.getUint16(20);
        const expectedParts = Math.ceil(totalBytes / MAX_FRAGMENT_BYTES);
        const expectedLength = totalBytes - part * MAX_FRAGMENT_BYTES;
        if (level > header.levels || !parts || part >= parts || !totalBytes ||
            totalBytes > MAX_TILE_BYTES || !dataLength || dataLength > MAX_FRAGMENT_BYTES ||
            parts !== expectedParts || dataLength !== Math.min(MAX_FRAGMENT_BYTES, expectedLength) ||
            packet.byteLength !== 22 + dataLength) throw new RangeError('fragmento ZT inválido');
        const geometry = geometries[level];
        if (tx >= geometry.tilesX || ty >= geometry.tilesY ||
            tileId !== tileStarts[level] + ty * geometry.tilesX + tx) {
            throw new RangeError('coordenadas ZT inválidas');
        }
        return { tileId, level, tx, ty, part, parts, totalBytes, data: packet.subarray(22) };
    }

    function setStatus(message, ok) {
        status.textContent = message;
        status.className = ok ? 'ok' : 'err';
    }

    function resizeCanvas() {
        canvas.width = canvas.clientWidth;
        canvas.height = canvas.clientHeight;
        needsDraw = true;
    }

    function fitView() {
        view.scale = Math.min(canvas.width / header.width, canvas.height / header.height);
        view.offsetX = header.width / 2 - canvas.width / (2 * view.scale);
        view.offsetY = header.height / 2 - canvas.height / (2 * view.scale);
        needsDraw = true;
    }

    function clearTiles() {
        for (const tile of tiles.values()) tile.bitmap?.close();
        tiles.clear();
        cacheBytes = 0;
        pendingDecodes = 0;
        clock = 0;
        needsDraw = true;
    }

    function openSession() {
        const token = ++generation;
        const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/stream`);
        ws = socket;
        socket.binaryType = 'arraybuffer';
        const maxRwnd = mode === 0 ? MAX_RWND_LEGACY : MAX_RWND_BATCH;
        const sessionReceiver = new Receiver(
            buffer => { if (socket.readyState === WebSocket.OPEN) socket.send(buffer); },
            packet => onPacket(packet, token),
            maxRwnd,
            validateZipPacket,
        );
        receiver = sessionReceiver;
        socket.onopen = () => {
            if (token !== generation) return;
            setStatus('conectado · ZIP', true);
            socket.send(encodeHello(mode, 2));
            sendViewport();
        };
        socket.onclose = () => { if (token === generation) setStatus('desconectado', false); };
        socket.onerror = () => { if (token === generation) setStatus('error de conexión', false); };
        socket.onmessage = event => {
            if (token !== generation) return;
            const data = event.data;
            if (!(data instanceof ArrayBuffer) || data.byteLength < 1) return;
            if (new Uint8Array(data, 0, 1)[0] !== T.DATA) return;
            try { sessionReceiver.onData(data); }
            catch { setStatus('datos inválidos', false); socket.close(); }
        };
    }

    function resetForMode(nextMode) {
        if (nextMode === mode) return;
        mode = nextMode;
        const previous = ws;
        ws = null;
        receiver = null;
        generation++;
        if (previous && previous.readyState < WebSocket.CLOSING) previous.close();
        clearTimeout(viewportTimer); viewportTimer = null;
        clearTimeout(panDecayTimer); panDecayTimer = null;
        lastViewportAt = 0;
        lastViewportKey = null;
        clearTiles();
        panHistory.length = 0;
        stats.packets = stats.bytes = stats.evicted = stats.duplicates = stats.rwnd = 0;
        statsEl.textContent = 'esperando datos…';
        setStatus('conectando', false);
        openSession();
        updateStats();
    }

    function onPacket(packet, token) {
        if (token !== generation) return;
        const fragment = parseZipPacket(packet);
        let tile = tiles.get(fragment.tileId);
        if (!tile) {
            tile = {
                tileId: fragment.tileId,
                level: fragment.level,
                tx: fragment.tx,
                ty: fragment.ty,
                parts: fragment.parts,
                totalBytes: fragment.totalBytes,
                fragments: new Array(fragment.parts),
                received: 0,
                bytes: 0,
                bitmap: null,
                decoding: false,
                lastUsed: ++clock,
                utility: 2 ** Math.min(30, fragment.level * 2),
            };
            tiles.set(tile.tileId, tile);
        } else if (tile.level !== fragment.level || tile.tx !== fragment.tx || tile.ty !== fragment.ty ||
            tile.parts !== fragment.parts || tile.totalBytes !== fragment.totalBytes) {
            throw new RangeError('fragmentos ZT incompatibles');
        }
        stats.packets++;
        stats.bytes += fragment.data.byteLength;
        tile.lastUsed = ++clock;
        if (!tile.fragments || tile.fragments[fragment.part]) {
            stats.duplicates++;
            return;
        }
        const copy = fragment.data.slice();
        tile.fragments[fragment.part] = copy;
        tile.received++;
        tile.bytes += copy.byteLength;
        cacheBytes += copy.byteLength;
        if (tile.received === tile.parts) decodeTile(tile, token);
        evict();
        updateRwnd();
    }

    async function decodeTile(tile, token) {
        tile.decoding = true;
        pendingDecodes++;
        try {
            if (tile.bytes !== tile.totalBytes) throw new RangeError('tamaño PNG ZT inválido');
            const png = new Uint8Array(tile.totalBytes);
            let offset = 0;
            for (const fragment of tile.fragments) { png.set(fragment, offset); offset += fragment.byteLength; }
            tile.fragments = null;
            const bitmap = await createImageBitmap(new Blob([png], { type: 'image/png' }));
            if (token !== generation || tiles.get(tile.tileId) !== tile) { bitmap.close(); return; }
            tile.bitmap = bitmap;
            const imageBytes = bitmap.width * bitmap.height * 4;
            cacheBytes += imageBytes - tile.bytes;
            tile.bytes = imageBytes;
            tile.decoding = false;
            tile.lastUsed = ++clock;
            needsDraw = true;
            evict();
        } catch {
            if (token === generation && tiles.get(tile.tileId) === tile) {
                cacheBytes -= tile.bytes;
                tiles.delete(tile.tileId);
                setStatus('PNG de tile inválido', false);
            }
        } finally {
            if (token === generation) {
                pendingDecodes--;
                updateRwnd();
            }
        }
    }

    function evict() {
        const overLimit = () => mode === 2 ? cacheBytes > MAX_CACHE_BYTES : tiles.size > MAX_TILES;
        if (!overLimit()) return;
        const isVisible = tile => {
            const scale = geometries[tile.level].sourceScale;
            const x = tile.tx * header.tileSize * scale;
            const y = tile.ty * header.tileSize * scale;
            const right = Math.min(header.width, x + header.tileSize * scale);
            const bottom = Math.min(header.height, y + header.tileSize * scale);
            return x < view.offsetX + canvas.width / view.scale && right > view.offsetX &&
                y < view.offsetY + canvas.height / view.scale && bottom > view.offsetY;
        };
        const candidates = [...tiles.values()].sort((a, b) => {
            const visibleA = isVisible(a), visibleB = isVisible(b);
            if (mode !== 2) return Number(visibleA) - Number(visibleB) || a.lastUsed - b.lastUsed;
            const scoreA = a.utility / Math.max(1, a.bytes);
            const scoreB = b.utility / Math.max(1, b.bytes);
            return Number(visibleA) - Number(visibleB) || scoreA - scoreB || a.lastUsed - b.lastUsed;
        });
        const forgotten = [];
        while (candidates.length && overLimit()) {
            const tile = candidates.shift();
            if (tiles.get(tile.tileId) !== tile) continue;
            tiles.delete(tile.tileId);
            tile.bitmap?.close();
            cacheBytes -= tile.bytes;
            forgotten.push(tile.tileId);
        }
        if (!forgotten.length) return;
        stats.evicted += forgotten.length;
        for (let offset = 0; offset < forgotten.length; offset += 65535) {
            const batch = forgotten.slice(offset, offset + 65535);
            if (ws?.readyState === WebSocket.OPEN) ws.send(encodeForget(batch));
        }
    }

    function updateRwnd() {
        if (!receiver) return;
        const min = mode === 0 ? MIN_RWND_LEGACY : MIN_RWND_BATCH;
        const max = mode === 0 ? MAX_RWND_LEGACY : MAX_RWND_BATCH;
        const headroom = mode === 2
            ? Math.max(0, Math.min(1, (MAX_CACHE_BYTES - cacheBytes) / MAX_CACHE_BYTES))
            : Math.max(0, Math.min(1, (MAX_TILES - tiles.size) / MAX_TILES));
        receiver.rwnd = Math.max(min, Math.min(max,
            Math.round(min + headroom * (1 - Math.min(1, pendingDecodes / 64)) * (max - min))));
        stats.rwnd = receiver.rwnd;
    }

    function requestLevel() {
        return Math.max(0, Math.min(header.levels, Math.round(header.levels + Math.log2(view.scale))));
    }

    function sendViewport() {
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        const x = Math.max(0, Math.min(header.width - 1, Math.floor(view.offsetX)));
        const y = Math.max(0, Math.min(header.height - 1, Math.floor(view.offsetY)));
        const width = Math.min(header.width - x, Math.ceil(canvas.width / view.scale));
        const height = Math.min(header.height - y, Math.ceil(canvas.height / view.scale));
        const level = requestLevel();
        const geometry = geometries[level];
        const scale = geometry.sourceScale;
        const tx0 = Math.floor(x / scale / header.tileSize);
        const ty0 = Math.floor(y / scale / header.tileSize);
        const tx1 = Math.floor((x + width - 1) / scale / header.tileSize);
        const ty1 = Math.floor((y + height - 1) / scale / header.tileSize);
        if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) > MAX_REQUEST_TILES) return;
        const prediction = mode === 2 ? predictPan() : null;
        const key = `${x}:${y}:${width}:${height}:${level}:` +
            (prediction ? `${Math.round(prediction.dx / 8)}:${Math.round(prediction.dy / 8)}:${Math.floor(prediction.confidence / 32)}` : '');
        if (key === lastViewportKey) return;
        ws.send(encodeViewport(x, y, Math.max(1, width), Math.max(1, height), level, prediction));
        lastViewportKey = key;
        lastViewportAt = performance.now();
    }

    function predictPan() {
        const now = performance.now();
        const recent = panHistory.filter(point => now - point.at <= 1500);
        if (!recent.length) return { dx: 0, dy: 0, confidence: 0 };
        let dx = 0, dy = 0;
        for (const point of recent) {
            const weight = Math.exp(-(now - point.at) / 600);
            dx += point.dx * weight;
            dy += point.dy * weight;
        }
        const scale = Math.max(Math.abs(dx), Math.abs(dy), 1);
        const age = now - recent[recent.length - 1].at;
        const confidence = 255 * Math.exp(-age / 1200) * Math.min(1, recent.length / 3);
        return { dx: 127 * dx / scale, dy: 127 * dy / scale, confidence };
    }

    function scheduleViewport() {
        needsDraw = true;
        clearTimeout(viewportTimer);
        const delay = Math.max(0, 25 - (performance.now() - lastViewportAt));
        viewportTimer = setTimeout(() => { viewportTimer = null; sendViewport(); }, delay);
    }

    function schedulePanDecay() {
        clearTimeout(panDecayTimer);
        if (mode !== 2 || !panHistory.length) return;
        panDecayTimer = setTimeout(() => {
            if (performance.now() - panHistory.at(-1).at >= 1500) panHistory.length = 0;
            sendViewport();
            if (panHistory.length) schedulePanDecay();
        }, 500);
    }

    function draw() {
        ctx.fillStyle = '#0b0e1a';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        const left = view.offsetX, top = view.offsetY;
        const right = left + canvas.width / view.scale;
        const bottom = top + canvas.height / view.scale;
        if (overviewBitmap) {
            const x0 = Math.max(0, left), y0 = Math.max(0, top);
            const x1 = Math.min(header.width, right), y1 = Math.min(header.height, bottom);
            if (x1 > x0 && y1 > y0) {
                ctx.imageSmoothingEnabled = true;
                ctx.drawImage(overviewBitmap, x0 * overviewBitmap.width / header.width,
                    y0 * overviewBitmap.height / header.height,
                    (x1 - x0) * overviewBitmap.width / header.width,
                    (y1 - y0) * overviewBitmap.height / header.height,
                    (x0 - left) * view.scale, (y0 - top) * view.scale,
                    (x1 - x0) * view.scale, (y1 - y0) * view.scale);
            }
        }
        const visible = [...tiles.values()].filter(tile => tile.bitmap).sort((a, b) => a.level - b.level);
        for (const tile of visible) {
            const geometry = geometries[tile.level];
            const sourceScale = geometry.sourceScale;
            const x0 = tile.tx * header.tileSize * sourceScale;
            const y0 = tile.ty * header.tileSize * sourceScale;
            const x1 = Math.min(header.width, x0 + tile.bitmap.width * sourceScale);
            const y1 = Math.min(header.height, y0 + tile.bitmap.height * sourceScale);
            const sx = Math.max(0, (left - x0) / sourceScale);
            const sy = Math.max(0, (top - y0) / sourceScale);
            const ex = Math.min(tile.bitmap.width, (right - x0) / sourceScale);
            const ey = Math.min(tile.bitmap.height, (bottom - y0) / sourceScale);
            if (x1 <= left || y1 <= top || x0 >= right || y0 >= bottom || ex <= sx || ey <= sy) continue;
            tile.lastUsed = ++clock;
            ctx.imageSmoothingEnabled = sourceScale !== 1;
            ctx.drawImage(tile.bitmap, sx, sy, ex - sx, ey - sy,
                (x0 + sx * sourceScale - left) * view.scale,
                (y0 + sy * sourceScale - top) * view.scale,
                (ex - sx) * sourceScale * view.scale,
                (ey - sy) * sourceScale * view.scale);
        }
    }

    function frame() {
        if (needsDraw) { draw(); needsDraw = false; }
        evict();
        updateRwnd();
        requestAnimationFrame(frame);
    }

    function updateStats() {
        if (!receiver) return;
        const cache = mode === 2
            ? `${(cacheBytes / 1048576).toFixed(1)}/256 MiB`
            : `${tiles.size}/${MAX_TILES} tiles`;
        statsEl.textContent = `paquetes: ${stats.packets} · datos: ${(stats.bytes / 1024).toFixed(1)} KB · ` +
            `entregados: ${receiver.delivered} · fuera de orden: ${receiver.ooo.size} · ` +
            `caché: ${cache} · desalojados: ${stats.evicted} · duplicados: ${stats.duplicates} · ` +
            `rwnd: ${stats.rwnd} · zoom: ${view.scale.toFixed(2)}×`;
    }

    canvas.addEventListener('wheel', event => {
        event.preventDefault();
        const rect = canvas.getBoundingClientRect();
        const mouseX = event.clientX - rect.left, mouseY = event.clientY - rect.top;
        const imageX = view.offsetX + mouseX / view.scale;
        const imageY = view.offsetY + mouseY / view.scale;
        view.scale = Math.max(0.02, Math.min(32, view.scale * (event.deltaY < 0 ? 1.2 : 1 / 1.2)));
        view.offsetX = imageX - mouseX / view.scale;
        view.offsetY = imageY - mouseY / view.scale;
        scheduleViewport();
    }, { passive: false });

    let dragging = false, lastX = 0, lastY = 0;
    canvas.addEventListener('mousedown', event => { dragging = true; lastX = event.clientX; lastY = event.clientY; });
    window.addEventListener('mouseup', () => { dragging = false; });
    window.addEventListener('mousemove', event => {
        if (!dragging) return;
        const dx = -(event.clientX - lastX) / view.scale;
        const dy = -(event.clientY - lastY) / view.scale;
        view.offsetX += dx;
        view.offsetY += dy;
        panHistory.push({ dx, dy, at: performance.now() });
        if (panHistory.length > 8) panHistory.shift();
        schedulePanDecay();
        lastX = event.clientX; lastY = event.clientY;
        scheduleViewport();
    });
    window.addEventListener('resize', () => { resizeCanvas(); scheduleViewport(); });
    document.getElementById('fit').addEventListener('click', () => { fitView(); scheduleViewport(); });
    modeEl.addEventListener('change', () => resetForMode(Number(modeEl.value)));

    resizeCanvas();
    fitView();
    if (header.hasOverview) {
        try {
            const response = await fetch('/api/overview');
            if (!response.ok) throw new Error('overview no disponible');
            overviewBitmap = await createImageBitmap(await response.blob());
        } catch { /* el primer tile RAPID puede servir como base */ }
    }
    openSession();
    if (!frameStarted) { frameStarted = true; requestAnimationFrame(frame); }
    if (statsTimer === null) statsTimer = setInterval(updateStats, 500);
}
