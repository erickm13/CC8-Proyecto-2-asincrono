package com.cc8.server.image;

/**
 * Transformada wavelet Haar reversible (S-transform) entera, en 2D y
 * multinivel, operando sobre un tile cuadrado de lado potencia de dos.
 *
 * <p>Se usa la variante reversible (sin perdida) para que los planos de bits
 * de los coeficientes sean exactos y la reconstruccion progresiva converja a
 * la imagen original:
 * <pre>
 *   Forward (por par a0,a1):  d = a1 - a0 ;  s = a0 + floor(d/2)
 *   Inverse (por par s,d):    a0 = s - floor(d/2) ;  a1 = a0 + d
 * </pre>
 * El desplazamiento aritmetico {@code >> 1} implementa floor(d/2) tambien para
 * valores negativos, que es justo lo que exige la S-transform.
 *
 * <p>Referencias: ISO/IEC 15444-1 (JPEG2000, transformada 5/3 reversible);
 * Calderbank et al., "Wavelet transforms that map integers to integers".
 */
public final class HaarWavelet {

    private HaarWavelet() {
    }

    /**
     * Aplica la DWT directa in situ sobre {@code data} (tile de lado
     * {@code size}, fila-mayor), con {@code levels} niveles de descomposicion.
     * Tras la llamada, la esquina superior izquierda de lado
     * {@code size >> levels} contiene la sub-banda LL mas gruesa.
     */
    public static void forward2D(int[] data, int size, int levels) {
        int current = size;
        for (int level = 0; level < levels; level++) {
            forwardStep(data, size, current);
            current >>= 1;
        }
    }

    /** Inversa de {@link #forward2D}. */
    public static void inverse2D(int[] data, int size, int levels) {
        // Reconstruir del nivel mas grueso al mas fino.
        int smallest = size >> levels;
        for (int level = 0; level < levels; level++) {
            int current = smallest << 1;
            inverseStep(data, size, current);
            smallest = current;
        }
    }

    // ---- Un nivel --------------------------------------------------------

    /** Transforma un nivel sobre la sub-region superior izquierda size x n. */
    private static void forwardStep(int[] data, int stride, int n) {
        int half = n >> 1;
        int[] tmp = new int[n];

        // Filas
        for (int y = 0; y < n; y++) {
            int base = y * stride;
            for (int i = 0; i < half; i++) {
                int a0 = data[base + 2 * i];
                int a1 = data[base + 2 * i + 1];
                int d = a1 - a0;
                int s = a0 + (d >> 1);
                tmp[i] = s;
                tmp[half + i] = d;
            }
            System.arraycopy(tmp, 0, data, base, n);
        }

        // Columnas
        for (int x = 0; x < n; x++) {
            for (int i = 0; i < half; i++) {
                int a0 = data[(2 * i) * stride + x];
                int a1 = data[(2 * i + 1) * stride + x];
                int d = a1 - a0;
                int s = a0 + (d >> 1);
                tmp[i] = s;
                tmp[half + i] = d;
            }
            for (int i = 0; i < n; i++) {
                data[i * stride + x] = tmp[i];
            }
        }
    }

    /** Inversa de un nivel sobre la sub-region superior izquierda size x n. */
    private static void inverseStep(int[] data, int stride, int n) {
        int half = n >> 1;
        int[] tmp = new int[n];

        // Columnas (inverso del orden directo: primero columnas)
        for (int x = 0; x < n; x++) {
            for (int i = 0; i < half; i++) {
                int s = data[i * stride + x];
                int d = data[(half + i) * stride + x];
                int a0 = s - (d >> 1);
                int a1 = a0 + d;
                tmp[2 * i] = a0;
                tmp[2 * i + 1] = a1;
            }
            for (int i = 0; i < n; i++) {
                data[i * stride + x] = tmp[i];
            }
        }

        // Filas
        for (int y = 0; y < n; y++) {
            int base = y * stride;
            for (int i = 0; i < half; i++) {
                int s = data[base + i];
                int d = data[base + half + i];
                int a0 = s - (d >> 1);
                int a1 = a0 + d;
                tmp[2 * i] = a0;
                tmp[2 * i + 1] = a1;
            }
            System.arraycopy(tmp, 0, data, base, n);
        }
    }
}
