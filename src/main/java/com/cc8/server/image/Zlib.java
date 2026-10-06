package com.cc8.server.image;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compresion sin perdida DEFLATE (del JDK, sin dependencias externas). Se usa
 * para comprimir cada paquete de plano de bits antes de escribirlo a disco.
 * En el navegador se descomprime con la API nativa DecompressionStream.
 */
public final class Zlib {

    private Zlib() {
    }

    public static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length / 2));
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    public static byte[] inflate(byte[] input, int expectedSize) {
        Inflater inflater = new Inflater(true);
        inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, expectedSize));
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("DEFLATE corrupto", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
