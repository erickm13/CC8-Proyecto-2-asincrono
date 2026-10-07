// Formato binario del protocolo RAPID (espejo de Wire.java / ImagePacket.java).

export const T = { HELLO: 1, DATA: 2, ACK: 3, VIEWPORT: 4, WIN: 5, FORGET: 6, FIN: 7 };
export const DATA_FLAG_BATCH = 0x02;

/** Abre una sesión: type, protocol version, scheduler mode. */
export function encodeHello(mode, version = 1) {
    if (!Number.isInteger(mode) || mode < 0 || mode > 2) throw new RangeError('modo RAPID inválido');
    if (version !== 1 && version !== 2) throw new RangeError('versión RAPID inválida');
    return new Uint8Array([T.HELLO, version, mode]).buffer;
}

/** Parsea un DATA:  type seq(4) sendTs(8) flags payloadLen(2) payload. */
export function parseData(buf) {
    if (buf.byteLength < 16) throw new RangeError('DATA incompleto');
    const dv = new DataView(buf);
    const u8 = new Uint8Array(buf);
    const seq = dv.getUint32(1);
    const sendTs = u8.slice(5, 13);          // 8 bytes opacos, se reflejan en el ACK
    const flags = u8[13];
    const payloadLen = dv.getUint16(14);
    if (buf.byteLength !== 16 + payloadLen) throw new RangeError('longitud DATA inválida');
    const payload = u8.subarray(16, 16 + payloadLen);
    return { seq, sendTs, flags, payload };
}

/** Lee paquetes de imagen legacy o el lote length-prefixed indicado por flags. */
export function parseDataPackets(payload, flags, validatePacket = parseImagePacket) {
    if (!(flags & DATA_FLAG_BATCH)) {
        validatePacket(payload);
        return [payload];
    }
    if (payload.byteLength > 32768) throw new RangeError('lote DATA demasiado grande');
    const packets = [];
    for (let offset = 0; offset < payload.length;) {
        if (packets.length === 16 || offset + 2 > payload.length) throw new RangeError('lote DATA inválido');
        const length = (payload[offset] << 8) | payload[offset + 1];
        offset += 2;
        if (!length || offset + length > payload.length) throw new RangeError('paquete del lote inválido');
        const packet = payload.subarray(offset, offset + length);
        validatePacket(packet);
        packets.push(packet);
        offset += length;
    }
    if (!packets.length) throw new RangeError('lote DATA vacío');
    return packets;
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
export function encodeViewport(x, y, w, h, zoom, prediction = null) {
    const buf = new ArrayBuffer(prediction ? 21 : 18);
    const dv = new DataView(buf);
    dv.setUint8(0, T.VIEWPORT);
    dv.setUint32(1, x); dv.setUint32(5, y);
    dv.setUint32(9, w); dv.setUint32(13, h);
    dv.setUint8(17, zoom);
    if (prediction) {
        dv.setInt8(18, clamp(Math.round(prediction.dx), -127, 127));
        dv.setInt8(19, clamp(Math.round(prediction.dy), -127, 127));
        dv.setUint8(20, clamp(Math.round(prediction.confidence), 0, 255));
    }
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
    if (u8.byteLength < 18) throw new RangeError('paquete de imagen incompleto');
    const dataLength = dv.getUint16(16);
    if (18 + dataLength !== u8.byteLength) throw new RangeError('longitud de paquete de imagen inválida');
    return {
        tile: dv.getUint32(0),
        comp: u8[4],
        level: u8[5],
        py: dv.getUint16(6),
        px: dv.getUint16(8),
        layer: u8[10],
        numPlanes: u8[11],
        numCoeffs: dv.getUint32(12),
        data: u8.subarray(18, 18 + dataLength),
    };
}

function clamp(value, lo, hi) { return Math.max(lo, Math.min(hi, value)); }
