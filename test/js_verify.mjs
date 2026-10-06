// Verifica el port JavaScript del cliente RAPID: usa los MISMOS módulos del
// navegador (wire/receiver/decode) contra el servidor real y compara la
// reconstrucción con el PPM de referencia (decodificación de Java, sin pérdida).
//
// Uso: node test/js_verify.mjs [http://host:puerto] [referencia.ppm]

import { readFileSync } from 'node:fs';
import { T, parseImagePacket, encodeViewport } from '../public/js/wire.js';
import { Receiver } from '../public/js/receiver.js';
import { inflateRaw, reconstructComponent } from '../public/js/decode.js';

const httpBase = process.argv[2] || 'http://localhost:8080';
const ppmPath = process.argv[3] || 'demo/ref.ppm';

function readPpm(path) {
    const buf = readFileSync(path);
    // Cabecera ASCII: "P6\n{w} {h}\n255\n"
    let pos = 0, fields = [];
    while (fields.length < 4) {
        while (buf[pos] === 0x20 || buf[pos] === 0x0a || buf[pos] === 0x09) pos++;
        let start = pos;
        while (buf[pos] !== 0x20 && buf[pos] !== 0x0a && buf[pos] !== 0x09) pos++;
        fields.push(buf.toString('ascii', start, pos));
    }
    pos++; // el byte único tras "255"
    const w = parseInt(fields[1]), h = parseInt(fields[2]);
    return { w, h, data: buf.subarray(pos) };
}

const main = async () => {
    const header = await (await fetch(httpBase + '/api/manifest')).json();
    console.log(`Manifiesto: ${header.width}x${header.height} comps=${header.components} levels=${header.levels}`);

    const tiles = new Map();
    const pending = [];
    let lastRecv = Date.now();

    function getTile(t) {
        if (!tiles.has(t)) {
            const comps = [];
            for (let c = 0; c < header.components; c++) comps.push(new Map());
            tiles.set(t, comps);
        }
        return tiles.get(t);
    }

    const onPacket = (payload) => {
        const pk = parseImagePacket(payload);
        const map = getTile(pk.tile)[pk.comp];
        const key = `${pk.level}_${pk.py}_${pk.px}`;
        let pr = map.get(key);
        if (!pr) {
            pr = { level: pk.level, py: pk.py, px: pk.px, numCoeffs: pk.numCoeffs,
                   numPlanes: pk.numPlanes, layers: new Array(pk.numPlanes), received: 0 };
            map.set(key, pr);
        }
        lastRecv = Date.now();
        // Copiar los datos (el ArrayBuffer del mensaje se reutiliza) e inflar.
        const data = pk.data.slice();
        pending.push(inflateRaw(data).then(inf => {
            pr.layers[pk.layer] = inf;
            let r = 0; while (r < pr.numPlanes && pr.layers[r]) r++;
            pr.received = r;
        }));
    };

    const ws = new WebSocket(httpBase.replace(/^http/, 'ws') + '/stream');
    ws.binaryType = 'arraybuffer';
    const receiver = new Receiver(buf => ws.send(buf), onPacket, 100000);

    await new Promise((resolve, reject) => {
        ws.onopen = resolve;
        ws.onerror = reject;
    });
    ws.onmessage = (e) => {
        const type = new Uint8Array(e.data, 0, 1)[0];
        if (type === T.DATA) receiver.onData(e.data);
    };
    console.log('WebSocket RAPID abierto.');
    ws.send(encodeViewport(0, 0, header.width, header.height, header.levels));

    // Quiescencia: sin DATA nuevo por 1.5 s.
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 200));
        if (Date.now() - lastRecv > 1500) break;
    }
    await Promise.all(pending);
    ws.close();
    console.log(`Recibidos: ${receiver.delivered} entregados, ${receiver.duplicates} duplicados`);

    // Reconstruir imagen completa.
    const { w, h, data: ref } = readPpm(ppmPath);
    if (w !== header.width || h !== header.height) throw new Error('dimensiones no coinciden');
    const ts = header.tileSize;
    let mismatches = 0, compared = 0;
    for (let ty = 0; ty < header.tilesY; ty++) {
        for (let tx = 0; tx < header.tilesX; tx++) {
            const tile = ty * header.tilesX + tx;
            const comps = getTile(tile);
            const rec = [];
            for (let c = 0; c < header.components; c++) {
                rec[c] = reconstructComponent(header, [...comps[c].values()]);
            }
            const x0 = tx * ts, y0 = ty * ts;
            const vw = Math.min(ts, w - x0), vh = Math.min(ts, h - y0);
            for (let yy = 0; yy < vh; yy++) {
                for (let xx = 0; xx < vw; xx++) {
                    const p = yy * ts + xx;
                    const r = header.components === 1 ? rec[0][p] : rec[0][p];
                    const g = header.components === 1 ? rec[0][p] : rec[1][p];
                    const b = header.components === 1 ? rec[0][p] : rec[2][p];
                    const o = ((y0 + yy) * w + (x0 + xx)) * 3;
                    compared++;
                    if (ref[o] !== r || ref[o + 1] !== g || ref[o + 2] !== b) mismatches++;
                }
            }
        }
    }
    console.log(`Comparados ${compared} pixeles, ${mismatches} distintos`);
    if (mismatches !== 0) { console.log('FALLO: el port JS no coincide con la referencia'); process.exit(1); }
    console.log('OK  el cliente JS reconstruye idéntico a la referencia de Java');
};

main().catch(e => { console.error(e); process.exit(1); });
