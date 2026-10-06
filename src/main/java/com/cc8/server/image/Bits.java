package com.cc8.server.image;

import java.io.ByteArrayOutputStream;

/**
 * Lectura/escritura de un flujo de bits (MSB primero dentro de cada byte).
 * Se usa para codificar los planos de bits de los coeficientes wavelet.
 */
public final class Bits {

    private Bits() {
    }

    /** Escritor de bits que acumula en un arreglo de bytes. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current = 0;
        private int filled = 0; // bits ocupados en current (0..7)

        public void writeBit(int bit) {
            current = (current << 1) | (bit & 1);
            filled++;
            if (filled == 8) {
                out.write(current);
                current = 0;
                filled = 0;
            }
        }

        /** Cierra el byte en curso rellenando con ceros y devuelve los bytes. */
        public byte[] toBytes() {
            if (filled > 0) {
                current <<= (8 - filled);
                out.write(current);
                current = 0;
                filled = 0;
            }
            return out.toByteArray();
        }
    }

    /** Lector de bits sobre un arreglo de bytes. */
    public static final class Reader {
        private final byte[] data;
        private int bytePos = 0;
        private int bitPos = 0; // 0..7, desde el MSB

        public Reader(byte[] data) {
            this.data = data;
        }

        public int readBit() {
            if (bytePos >= data.length) {
                return 0; // relleno defensivo
            }
            int bit = (data[bytePos] >> (7 - bitPos)) & 1;
            bitPos++;
            if (bitPos == 8) {
                bitPos = 0;
                bytePos++;
            }
            return bit;
        }
    }
}
