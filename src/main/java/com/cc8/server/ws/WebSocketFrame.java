package com.cc8.server.ws;

import java.io.ByteArrayOutputStream;

/**
 * Codificacion y decodificacion de frames WebSocket (RFC 6455, seccion 5.2).
 *
 * <pre>
 *   byte 0: FIN(1) RSV(3)=0 opcode(4)
 *   byte 1: MASK(1) payloadLen(7)
 *   [len extendido 2 o 8 bytes]  [masking-key 4 bytes si MASK]  [payload]
 * </pre>
 * Los frames del cliente vienen SIEMPRE enmascarados; los del servidor van
 * SIEMPRE sin mascara.
 */
public final class WebSocketFrame {

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    private WebSocketFrame() {
    }

    /** Frame decodificado. */
    public record Frame(boolean fin, int opcode, byte[] payload) {
    }

    /** Resultado del parseo: frame + bytes consumidos. */
    public record ParseResult(Frame frame, int consumed) {
    }

    /**
     * Intenta parsear un frame desde data[offset..offset+length). Devuelve null
     * si aun no hay bytes suficientes (hay que leer mas).
     */
    public static ParseResult tryParse(byte[] data, int offset, int length) {
        if (length < 2) {
            return null;
        }
        int p = offset;
        int b0 = data[p++] & 0xFF;
        int b1 = data[p++] & 0xFF;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7F;

        int need = 2;
        if (payloadLen == 126) {
            need += 2;
            if (length < need) return null;
            payloadLen = ((data[p] & 0xFFL) << 8) | (data[p + 1] & 0xFFL);
            p += 2;
        } else if (payloadLen == 127) {
            need += 8;
            if (length < need) return null;
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (data[p + i] & 0xFFL);
            }
            p += 8;
        }

        byte[] maskKey = null;
        if (masked) {
            need += 4;
            if (length < need) return null;
            maskKey = new byte[]{data[p], data[p + 1], data[p + 2], data[p + 3]};
            p += 4;
        }

        need += (int) payloadLen;
        if (length < need) {
            return null;
        }

        byte[] payload = new byte[(int) payloadLen];
        for (int i = 0; i < payload.length; i++) {
            int v = data[p + i] & 0xFF;
            if (masked) {
                v ^= (maskKey[i & 3] & 0xFF);
            }
            payload[i] = (byte) v;
        }
        return new ParseResult(new Frame(fin, opcode, payload), need);
    }

    /** Codifica un frame del servidor (sin mascara). */
    public static byte[] encode(int opcode, byte[] payload, boolean fin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 10);
        out.write((fin ? 0x80 : 0x00) | (opcode & 0x0F));

        int len = payload.length;
        if (len < 126) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((long) len >>> (8 * i)) & 0xFF);
            }
        }
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    public static byte[] binary(byte[] payload) {
        return encode(OP_BINARY, payload, true);
    }

    public static byte[] pong(byte[] payload) {
        return encode(OP_PONG, payload, true);
    }

    public static byte[] close() {
        return encode(OP_CLOSE, new byte[0], true);
    }
}
