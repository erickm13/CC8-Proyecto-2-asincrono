// Verifica el ciclo LRU (FORGET -> reenvío) sobre el servidor real.
// Uso: node test/forget_verify.mjs [http://host:puerto]

import { T, parseImagePacket, encodeViewport, encodeForget } from '../public/js/wire.js';
import { Receiver } from '../public/js/receiver.js';

const httpBase = process.argv[2] || 'http://localhost:8080';

const perTile = new Map();
let lastRecv = Date.now();
const onPacket = (payload) => {
    const pk = parseImagePacket(payload);
    perTile.set(pk.tile, (perTile.get(pk.tile) || 0) + 1);
    lastRecv = Date.now();
};

const quiesce = async () => {
    lastRecv = Date.now();
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 150));
        if (Date.now() - lastRecv > 1200) break;
    }
};

const main = async () => {
    const h = await (await fetch(httpBase + '/api/manifest')).json();
    const ws = new WebSocket(httpBase.replace(/^http/, 'ws') + '/stream');
    ws.binaryType = 'arraybuffer';
    const receiver = new Receiver(buf => ws.send(buf), onPacket, 100000);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (e) => { if (new Uint8Array(e.data, 0, 1)[0] === T.DATA) receiver.onData(e.data); };

    // Pase 1: pedir todo.
    ws.send(encodeViewport(0, 0, h.width, h.height, h.levels));
    await quiesce();
    const total1 = receiver.delivered;
    const c0 = perTile.get(0) || 0, c1 = perTile.get(1) || 0;
    console.log(`Pase 1: ${total1} paquetes (tile0=${c0}, tile1=${c1})`);

    // Olvidar tiles 0 y 1, volver a pedir.
    ws.send(encodeForget([0, 1]));
    ws.send(encodeViewport(0, 0, h.width, h.height, h.levels));
    await quiesce();
    const total2 = receiver.delivered;
    const resent = total2 - total1;
    console.log(`Pase 2: +${resent} paquetes reenviados tras FORGET(0,1)`);

    ws.close();
    if (resent !== c0 + c1) {
        console.log(`FALLO: reenviados=${resent} != tile0+tile1=${c0 + c1}`);
        process.exit(1);
    }
    console.log('OK  tras FORGET se reenvían exactamente los paquetes de los tiles olvidados');
};

main().catch(e => { console.error(e); process.exit(1); });
