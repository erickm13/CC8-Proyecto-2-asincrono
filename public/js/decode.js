// Decodificación del lado del cliente: espejo exacto de ClientReconstructor.java.
// Inflado DEFLATE (nativo) + bit-planes + DWT Haar inversa + scatter.

/** Descomprime DEFLATE crudo (coincide con Deflater(nowrap=true) del servidor). */
export async function inflateRaw(u8) {
    const ds = new DecompressionStream('deflate-raw');
    const stream = new Blob([u8]).stream().pipeThrough(ds);
    const ab = await new Response(stream).arrayBuffer();
    return new Uint8Array(ab);
}

/** Lector de bits MSB-first (igual que Bits.Reader de Java). */
class BitReader {
    constructor(u8) { this.d = u8; this.byte = 0; this.bit = 0; }
    readBit() {
        if (this.byte >= this.d.length) return 0;
        const b = (this.d[this.byte] >> (7 - this.bit)) & 1;
        if (++this.bit === 8) { this.bit = 0; this.byte++; }
        return b;
    }
}

/** Reconstruye coeficientes de un precinct desde sus planos de bits recibidos. */
function decodeCoeffs(layers, received, n, numPlanes) {
    const mag = new Int32Array(n);
    const sig = new Uint8Array(n);
    const sign = new Int8Array(n);
    for (let layer = 0; layer < received; layer++) {
        const raw = layers[layer];
        if (!raw) break;
        const plane = numPlanes - 1 - layer;
        const r = new BitReader(raw);
        for (let i = 0; i < n; i++) {
            if (r.readBit() === 1) {
                mag[i] |= (1 << plane);
                if (!sig[i]) { sig[i] = 1; sign[i] = r.readBit() === 1 ? -1 : 1; }
            }
        }
    }
    const out = new Int32Array(n);
    for (let i = 0; i < n; i++) out[i] = sig[i] ? sign[i] * mag[i] : 0;
    return out;
}

/** Nivel de resolución del coeficiente (igual que Precincts.resolutionLevel). */
function resolutionLevel(y, x, s0) {
    let m = Math.max(y, x);
    if (m < s0) return 0;
    let k = 1;
    while ((s0 << k) <= m) k++;
    return k;
}

/** DWT Haar inversa 2D multinivel (igual que HaarWavelet.inverse2D). */
function inverse2D(data, size, levels) {
    let smallest = size >> levels;
    for (let level = 0; level < levels; level++) {
        const cur = smallest << 1;
        inverseStep(data, size, cur);
        smallest = cur;
    }
}

function inverseStep(data, stride, n) {
    const half = n >> 1;
    const tmp = new Int32Array(n);
    // columnas
    for (let x = 0; x < n; x++) {
        for (let i = 0; i < half; i++) {
            const s = data[i * stride + x];
            const d = data[(half + i) * stride + x];
            const a0 = s - (d >> 1);
            tmp[2 * i] = a0;
            tmp[2 * i + 1] = a0 + d;
        }
        for (let i = 0; i < n; i++) data[i * stride + x] = tmp[i];
    }
    // filas
    for (let y = 0; y < n; y++) {
        const base = y * stride;
        for (let i = 0; i < half; i++) {
            const s = data[base + i];
            const d = data[base + half + i];
            const a0 = s - (d >> 1);
            tmp[2 * i] = a0;
            tmp[2 * i + 1] = a0 + d;
        }
        for (let i = 0; i < n; i++) data[base + i] = tmp[i];
    }
}

/**
 * Reconstruye un componente (tileSize x tileSize) desde los precincts recibidos.
 * precinctList: [{level,py,px,numCoeffs,numPlanes,layers,received}]
 * Devuelve Uint8ClampedArray de pixeles 0..255.
 */
export function reconstructComponent(header, precinctList) {
    const ts = header.tileSize, levels = header.levels, precinct = header.precinct;
    const decoded = new Map();
    for (const p of precinctList) {
        decoded.set(`${p.level}_${p.py}_${p.px}`,
            decodeCoeffs(p.layers, p.received, p.numCoeffs, p.numPlanes));
    }
    const s0 = ts >> levels;
    const coeff = new Int32Array(ts * ts);
    const cursor = new Map();
    for (let y = 0; y < ts; y++) {
        for (let x = 0; x < ts; x++) {
            const level = resolutionLevel(y, x, s0);
            const key = `${level}_${(y / precinct) | 0}_${(x / precinct) | 0}`;
            const vals = decoded.get(key);
            if (!vals) continue;
            const c = cursor.get(key) || 0;
            cursor.set(key, c + 1);
            coeff[y * ts + x] = vals[c];
        }
    }
    inverse2D(coeff, ts, levels);
    const out = new Uint8ClampedArray(ts * ts);
    for (let i = 0; i < coeff.length; i++) out[i] = coeff[i] + 128; // clamp automático
    return out;
}
