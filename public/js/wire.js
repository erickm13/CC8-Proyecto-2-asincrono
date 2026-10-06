// Formato binario del protocolo RAPID (espejo de Wire.java / ImagePacket.java).

export const T = { HELLO: 1, DATA: 2, ACK: 3, VIEWPORT: 4, WIN: 5, FORGET: 6, FIN: 7 };

/** Parsea un DATA:  type seq(4) sendTs(8) flags payloadLen(2) payload. */
export function parseData(buf) {
    const dv = new DataView(buf);
    const u8 = new Uint8Array(buf);
    const seq = dv.getUint32(1);
    const sendTs = u8.slice(5, 13);          // 8 bytes opacos, se reflejan en el ACK
    const flags = u8[13];
    const payloadLen = dv.getUint16(14);
    const payload = u8.subarray(16, 16 + payloadLen);
    return { seq, sendTs, flags, payload };
}

/** Construye un ACK: type ack(4) rwnd(4) echoTs(8) nSack [start(4) end(4)]*. */
export function encodeAck(ack, rwnd, sendTsBytes, sackBlocks) {
    const n = sackBlocks.length;
    const buf = new ArrayBuffer(18 + n * 8);
    const dv = new DataView(buf);
    const u8 = new Uint8Array(buf);
    u8[0] = T.ACK;
    dv.setUint32(1, ack);
    dv.setUint32(5, rwnd);
    u8.set(sendTsBytes, 9);
    u8[17] = n;
    let o = 18;
    for (const b of sackBlocks) { dv.setUint32(o, b[0]); dv.setUint32(o + 4, b[1]); o += 8; }
    return buf;
}

/** Construye un VIEWPORT: type x(4) y(4) w(4) h(4) zoom(1). */
export function encodeViewport(x, y, w, h, zoom) {
    const buf = new ArrayBuffer(18);
    const dv = new DataView(buf);
    dv.setUint8(0, T.VIEWPORT);
    dv.setUint32(1, x); dv.setUint32(5, y);
    dv.setUint32(9, w); dv.setUint32(13, h);
    dv.setUint8(17, zoom);
    return buf;
}

/** Construye un FORGET: type count(2) [tile(4)]*. */
export function encodeForget(tileList) {
    const n = tileList.length;
    const buf = new ArrayBuffer(3 + n * 4);
    const dv = new DataView(buf);
    dv.setUint8(0, T.FORGET);
    dv.setUint16(1, n);
    for (let i = 0; i < n; i++) dv.setUint32(3 + i * 4, tileList[i]);
    return buf;
}

/** Parsea la cabecera de aplicación de un paquete de imagen (18 bytes + data). */
export function parseImagePacket(u8) {
    const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    return {
        tile: dv.getUint32(0),
        comp: u8[4],
        level: u8[5],
        py: dv.getUint16(6),
        px: dv.getUint16(8),
        layer: u8[10],
        numPlanes: u8[11],
        numCoeffs: dv.getUint32(12),
        data: u8.subarray(18, 18 + dv.getUint16(16)),
    };
}
