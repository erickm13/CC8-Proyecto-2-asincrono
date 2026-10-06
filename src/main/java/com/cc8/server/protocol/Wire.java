package com.cc8.server.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Formato binario de los segmentos del protocolo de transporte (big-endian).
 *
 * <p>Direccion de datos: servidor -> cliente (DATA). El cliente confirma con
 * ACK acumulativo + bloques SACK (RFC 2018) y anuncia su ventana de recepcion
 * rwnd (flow control). El numero de secuencia (SEQ) cuenta SEGMENTOS, no bytes:
 * cada DATA transporta un paquete de imagen autodescriptivo.
 *
 * <pre>
 *   DATA:  type(1)=2 | seq(4) | sendTs(8) | flags(1) | payloadLen(2) | payload
 *   ACK :  type(1)=3 | ack(4) | rwnd(4) | echoTs(8) | nSack(1) | [start(4) end(4)]*
 *   Control (VIEWPORT/WIN/HELLO/FIN): definidos en la integracion.
 * </pre>
 * flags: bit0 = retransmision (informativo/telemetria).
 */
public final class Wire {

    public static final byte T_HELLO = 1;
    public static final byte T_DATA = 2;
    public static final byte T_ACK = 3;
    public static final byte T_VIEWPORT = 4;
    public static final byte T_WIN = 5;
    public static final byte T_FORGET = 6;   // cliente desalojó tiles (LRU) -> reenviar si reaparecen
    public static final byte T_FIN = 7;

    public static final int FLAG_RETX = 0x01;

    private Wire() {
    }

    public static byte type(byte[] b) {
        return b[0];
    }

    // ---- DATA ------------------------------------------------------------

    public record Data(int seq, long sendTs, int flags, byte[] payload) {
    }

    public static byte[] encodeData(int seq, long sendTs, int flags, byte[] payload) {
        byte[] b = new byte[1 + 4 + 8 + 1 + 2 + payload.length];
        int p = 0;
        b[p++] = T_DATA;
        p = putInt(b, p, seq);
        p = putLong(b, p, sendTs);
        b[p++] = (byte) flags;
        p = putShort(b, p, payload.length);
        System.arraycopy(payload, 0, b, p, payload.length);
        return b;
    }

    public static Data decodeData(byte[] b) {
        int p = 1;
        int seq = getInt(b, p); p += 4;
        long ts = getLong(b, p); p += 8;
        int flags = b[p++] & 0xFF;
        int len = getShort(b, p); p += 2;
        byte[] payload = new byte[len];
        System.arraycopy(b, p, payload, 0, len);
        return new Data(seq, ts, flags, payload);
    }

    // ---- ACK -------------------------------------------------------------

    public record Ack(int ack, int rwnd, long echoTs, int[] sackStart, int[] sackEnd) {
    }

    public static byte[] encodeAck(int ack, int rwnd, long echoTs,
                                   int[] sackStart, int[] sackEnd) {
        int n = sackStart.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(18 + n * 8);
        byte[] head = new byte[1 + 4 + 4 + 8 + 1];
        int p = 0;
        head[p++] = T_ACK;
        p = putInt(head, p, ack);
        p = putInt(head, p, rwnd);
        p = putLong(head, p, echoTs);
        head[p++] = (byte) n;
        out.writeBytes(head);
        for (int i = 0; i < n; i++) {
            byte[] blk = new byte[8];
            putInt(blk, 0, sackStart[i]);
            putInt(blk, 4, sackEnd[i]);
            out.writeBytes(blk);
        }
        return out.toByteArray();
    }

    public static Ack decodeAck(byte[] b) {
        int p = 1;
        int ack = getInt(b, p); p += 4;
        int rwnd = getInt(b, p); p += 4;
        long echoTs = getLong(b, p); p += 8;
        int n = b[p++] & 0xFF;
        int[] s = new int[n];
        int[] e = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = getInt(b, p); p += 4;
            e[i] = getInt(b, p); p += 4;
        }
        return new Ack(ack, rwnd, echoTs, s, e);
    }

    // ---- Utilidades big-endian ------------------------------------------

    public static int putShort(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
        return o + 2;
    }

    public static int putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
        return o + 4;
    }

    public static int putLong(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) {
            b[o + i] = (byte) (v >>> (56 - 8 * i));
        }
        return o + 8;
    }

    public static int getShort(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    public static int getInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static long getLong(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[o + i] & 0xFF);
        }
        return v;
    }
}
