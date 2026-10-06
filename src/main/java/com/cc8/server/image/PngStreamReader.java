package com.cc8.server.image;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;

/**
 * Decodificador PNG por streaming (JDK puro, sin librerias). Lee la imagen
 * escanline por escanline manteniendo en memoria solo dos filas, por lo que
 * puede procesar PNGs de decenas de GB (p.ej. 75471x75471) sin agotar la RAM.
 *
 * <p>Soporta PNG de 8 bits, no entrelazado, tipos de color 0 (gris), 2 (RGB),
 * 4 (gris+alfa) y 6 (RGBA); el alfa se descarta. Aplica los 5 filtros de
 * des-filtrado del estandar (None/Sub/Up/Average/Paeth, RFC 2083 / ISO 15948).
 */
public final class PngStreamReader implements RowSource {

    private final InputStream file;
    private final InflaterInputStream inflate;

    private final int width;
    private final int height;
    private final int rawChannels;   // canales en el PNG (1,2,3,4)
    private final int components;    // canales de salida (1 o 3)
    private final int stride;        // bytes por fila filtrada = width*rawChannels

    private byte[] prev;
    private byte[] cur;
    private int rowsRead = 0;

    public PngStreamReader(Path path) throws IOException {
        this.file = new BufferedInputStream(Files.newInputStream(path), 1 << 20);
        DataInputStream in = new DataInputStream(file);

        long sig = in.readLong();
        if (sig != 0x89504E470D0A1A0AL) {
            throw new IOException("No es un PNG valido");
        }

        int w = 0, h = 0, bitDepth = 0, colorType = 0, interlace = 0;
        long firstIdatLen = -1;

        // Leer chunks hasta el primer IDAT.
        while (true) {
            int len = in.readInt();
            int type = in.readInt();
            if (type == 0x49484452) {            // IHDR
                w = in.readInt();
                h = in.readInt();
                bitDepth = in.readUnsignedByte();
                colorType = in.readUnsignedByte();
                in.readUnsignedByte();           // compression
                in.readUnsignedByte();           // filter
                interlace = in.readUnsignedByte();
                in.readInt();                    // CRC
            } else if (type == 0x49444154) {     // IDAT (primer bloque)
                firstIdatLen = len & 0xFFFFFFFFL;
                break;
            } else if (type == 0x49454E44) {     // IEND sin datos
                throw new IOException("PNG sin datos de imagen");
            } else {
                in.skipNBytes(len + 4L);         // datos + CRC
            }
        }

        if (bitDepth != 8 || interlace != 0) {
            throw new IOException("Solo se soporta PNG de 8 bits no entrelazado (bitDepth="
                    + bitDepth + ", interlace=" + interlace + ")");
        }
        this.rawChannels = switch (colorType) {
            case 0 -> 1;   // gris
            case 2 -> 3;   // RGB
            case 4 -> 2;   // gris + alfa
            case 6 -> 4;   // RGBA
            default -> throw new IOException("colorType no soportado: " + colorType
                    + " (¿paleta? conviértela a RGB)");
        };
        this.components = (colorType == 2 || colorType == 6) ? 3 : 1;
        this.width = w;
        this.height = h;
        this.stride = w * rawChannels;
        this.prev = new byte[stride];
        this.cur = new byte[stride];

        // El flujo zlib abarca todos los IDAT concatenados.
        this.inflate = new InflaterInputStream(new IdatInputStream(file, firstIdatLen));
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int components() { return components; }

    @Override
    public int readStrip(byte[] strip, int rows) throws IOException {
        int filled = 0;
        for (int r = 0; r < rows; r++) {
            if (rowsRead >= height || !nextScanline()) {
                break;
            }
            int base = r * width * components;
            if (components == 3) {
                for (int x = 0; x < width; x++) {
                    int s = x * rawChannels;
                    strip[base + x * 3] = cur[s];
                    strip[base + x * 3 + 1] = cur[s + 1];
                    strip[base + x * 3 + 2] = cur[s + 2];
                }
            } else {
                for (int x = 0; x < width; x++) {
                    strip[base + x] = cur[x * rawChannels];
                }
            }
            filled++;
        }
        return filled;
    }

    /** Lee y des-filtra la siguiente escanline; el resultado queda en {@code cur}. */
    private boolean nextScanline() throws IOException {
        // La fila actual pasa a ser la anterior; 'cur' se reutiliza como destino.
        byte[] t = prev;
        prev = cur;
        cur = t;
        int filter = inflate.read();
        if (filter < 0) {
            return false;
        }
        readFully(inflate, cur, stride);
        unfilter(filter);
        rowsRead++;
        return true;
    }

    private void unfilter(int filter) {
        int bpp = rawChannels;
        switch (filter) {
            case 0 -> { /* None */ }
            case 1 -> { // Sub
                for (int i = bpp; i < stride; i++) {
                    cur[i] = (byte) ((cur[i] & 0xFF) + (cur[i - bpp] & 0xFF));
                }
            }
            case 2 -> { // Up
                for (int i = 0; i < stride; i++) {
                    cur[i] = (byte) ((cur[i] & 0xFF) + (prev[i] & 0xFF));
                }
            }
            case 3 -> { // Average
                for (int i = 0; i < stride; i++) {
                    int a = i >= bpp ? (cur[i - bpp] & 0xFF) : 0;
                    int b = prev[i] & 0xFF;
                    cur[i] = (byte) ((cur[i] & 0xFF) + ((a + b) >> 1));
                }
            }
            case 4 -> { // Paeth
                for (int i = 0; i < stride; i++) {
                    int a = i >= bpp ? (cur[i - bpp] & 0xFF) : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= bpp ? (prev[i - bpp] & 0xFF) : 0;
                    cur[i] = (byte) ((cur[i] & 0xFF) + paeth(a, b, c));
                }
            }
            default -> throw new IllegalStateException("Filtro PNG invalido: " + filter);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new IOException("EOF inesperado en la escanline");
            }
            off += n;
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /**
     * InputStream que expone unicamente los bytes de datos de los chunks IDAT
     * (concatenados), saltando encabezados, CRCs y otros chunks. Asi el flujo
     * zlib de PNG puede leerse de corrido.
     */
    private static final class IdatInputStream extends InputStream {
        private final InputStream base;
        private final DataInputStream din;
        private long remaining;      // bytes de datos IDAT restantes en el chunk actual
        private boolean ended = false;

        IdatInputStream(InputStream base, long firstIdatLen) {
            this.base = base;
            this.din = new DataInputStream(base);
            this.remaining = firstIdatLen;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (ended) {
                return -1;
            }
            if (remaining == 0 && !advance()) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = base.read(b, off, toRead);
            if (n < 0) {
                ended = true;
                return -1;
            }
            remaining -= n;
            return n;
        }

        /** Salta el CRC del chunk actual y busca el siguiente IDAT. */
        private boolean advance() throws IOException {
            din.readInt(); // CRC del chunk anterior
            while (true) {
                int len = din.readInt();
                int type = din.readInt();
                if (type == 0x49444154) {        // IDAT
                    remaining = len & 0xFFFFFFFFL;
                    return true;
                } else if (type == 0x49454E44) { // IEND
                    ended = true;
                    return false;
                } else {
                    din.skipNBytes(len + 4L);    // otros chunks: datos + CRC
                }
            }
        }
    }
}
