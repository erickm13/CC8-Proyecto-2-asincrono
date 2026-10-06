package com.cc8.server.protocol;

/**
 * Curva de Hilbert: mapea un indice lineal d a coordenadas (x,y) de una rejilla
 * de lado potencia de dos, y viceversa. Preserva localidad: puntos cercanos en
 * d quedan cercanos en 2D, por lo que sirve para definir el ORDEN DE PROGRESION
 * de los precincts (nuestra alternativa propia a los ordenes LRCP/RPCL de
 * JPEG2000): la imagen se llena de forma espacialmente homogenea y las regiones
 * vecinas llegan juntas.
 *
 * <p>Referencia: D. Hilbert (1891); algoritmo de conversion clasico.
 */
public final class HilbertCurve {

    private HilbertCurve() {
    }

    /** Menor potencia de dos >= v (>=1). */
    public static int nextPow2(int v) {
        int n = 1;
        while (n < v) {
            n <<= 1;
        }
        return n;
    }

    /** (x,y) -> distancia d a lo largo de la curva, en una rejilla n x n. */
    public static long xy2d(int n, int x, int y) {
        long d = 0;
        for (int s = n / 2; s > 0; s /= 2) {
            int rx = (x & s) > 0 ? 1 : 0;
            int ry = (y & s) > 0 ? 1 : 0;
            d += (long) s * s * ((3L * rx) ^ ry);
            // rotacion del cuadrante
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                int t = x;
                x = y;
                y = t;
            }
        }
        return d;
    }

    /** distancia d -> (x,y) en una rejilla n x n. Escribe en out[0]=x, out[1]=y. */
    public static void d2xy(int n, long d, int[] out) {
        int x = 0, y = 0;
        long t = d;
        for (int s = 1; s < n; s *= 2) {
            int rx = (int) (1 & (t / 2));
            int ry = (int) (1 & (t ^ rx));
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                int tmp = x;
                x = y;
                y = tmp;
            }
            x += s * rx;
            y += s * ry;
            t /= 4;
        }
        out[0] = x;
        out[1] = y;
    }
}
