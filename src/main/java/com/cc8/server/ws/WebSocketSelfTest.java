package com.cc8.server.ws;

import java.util.Random;

/** Verifica el handshake (ejemplo del RFC 6455) y el round-trip de frames. */
public final class WebSocketSelfTest {
    public static void main(String[] args) {
        // 1) Ejemplo del RFC 6455, seccion 1.3.
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        String expected = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String got = WebSocketHandshake.accept(key);
        if (!expected.equals(got)) {
            System.out.printf("FALLO handshake: %s != %s%n", got, expected);
            System.exit(1);
        }
        System.out.println("OK  Sec-WebSocket-Accept coincide con el ejemplo del RFC");

        // 2) Round-trip de un frame binario enmascarado (como lo manda un cliente).
        Random rnd = new Random(3);
        for (int len : new int[]{0, 5, 125, 126, 200, 70000}) {
            byte[] payload = new byte[len];
            rnd.nextBytes(payload);
            byte[] framed = maskedClientFrame(payload, rnd);
            WebSocketFrame.ParseResult r = WebSocketFrame.tryParse(framed, 0, framed.length);
            if (r == null || r.consumed() != framed.length) {
                System.out.printf("FALLO parseo len=%d%n", len);
                System.exit(1);
            }
            byte[] out = r.frame().payload();
            if (out.length != len) {
                System.out.printf("FALLO longitud len=%d got=%d%n", len, out.length);
                System.exit(1);
            }
            for (int i = 0; i < len; i++) {
                if (out[i] != payload[i]) {
                    System.out.printf("FALLO contenido len=%d en i=%d%n", len, i);
                    System.exit(1);
                }
            }
            if (r.frame().opcode() != WebSocketFrame.OP_BINARY) {
                System.out.println("FALLO opcode");
                System.exit(1);
            }
        }
        System.out.println("OK  frames enmascarados (0..70000 bytes) round-trip correcto");

        // 3) Parseo incompleto devuelve null.
        byte[] partial = maskedClientFrame(new byte[100], rnd);
        if (WebSocketFrame.tryParse(partial, 0, 3) != null) {
            System.out.println("FALLO: parseo incompleto deberia dar null");
            System.exit(1);
        }
        System.out.println("OK  parseo incompleto devuelve null");
        System.out.println("Todas las pruebas de WebSocket pasaron.");
    }

    /** Construye un frame binario enmascarado tal como lo enviaria un cliente. */
    private static byte[] maskedClientFrame(byte[] payload, Random rnd) {
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x80 | WebSocketFrame.OP_BINARY);
        int len = payload.length;
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) out.write((int) ((long) len >>> (8 * i)) & 0xFF);
        }
        out.write(mask, 0, 4);
        for (int i = 0; i < len; i++) {
            out.write((payload[i] & 0xFF) ^ (mask[i & 3] & 0xFF));
        }
        return out.toByteArray();
    }
}
