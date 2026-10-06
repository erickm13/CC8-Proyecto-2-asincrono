package com.cc8.server.image;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reparto de los coeficientes wavelet de un tile en unidades
 * (resolution level, precinct), imitando el modelo de JPEG2000.
 *
 * <p>El nivel de resolucion de un coeficiente depende de en que sub-banda de
 * la piramide wavelet cae; el precinct es una subdivision espacial regular
 * dentro del tile. La agrupacion es determinista (recorrido fila-mayor), de
 * modo que codificador y decodificador reconstruyen las mismas posiciones sin
 * necesidad de almacenarlas.
 */
public final class Precincts {

    private Precincts() {
    }

    /**
     * Nivel de resolucion del coeficiente en (y,x). El nivel 0 es la sub-banda
     * LL mas gruesa (esquina superior izquierda de lado {@code s0}); los
     * niveles 1..levels agregan detalle cada vez mas fino.
     */
    public static int resolutionLevel(int y, int x, int s0) {
        int m = Math.max(y, x);
        if (m < s0) {
            return 0;
        }
        int k = 1;
        while ((s0 << k) <= m) {
            k++;
        }
        return k;
    }

    /** Clave compacta (level, py, px) -> long. py,px < 2^20. */
    public static long key(int level, int py, int px) {
        return ((long) level << 40) | ((long) py << 20) | (px & 0xFFFFF);
    }

    public static int levelOf(long key) {
        return (int) (key >>> 40);
    }

    public static int pyOf(long key) {
        return (int) ((key >>> 20) & 0xFFFFF);
    }

    public static int pxOf(long key) {
        return (int) (key & 0xFFFFF);
    }

    /**
     * Agrupa los coeficientes del tile (ya transformado) en buckets por
     * (level, precinct), en orden fila-mayor. Devuelve un mapa que conserva el
     * orden de primera aparicion.
     */
    public static Map<Long, int[]> bucketize(int[] coeff, int tileSize,
                                              int levels, int precinct) {
        int s0 = tileSize >> levels;

        // Pass 1: contar coeficientes por bucket.
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (int y = 0; y < tileSize; y++) {
            for (int x = 0; x < tileSize; x++) {
                long k = keyAt(y, x, s0, precinct);
                counts.merge(k, 1, Integer::sum);
            }
        }

        // Pass 2: rellenar cada bucket en orden fila-mayor.
        Map<Long, int[]> buckets = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            buckets.put(e.getKey(), new int[e.getValue()]);
        }
        Map<Long, Integer> cursor = new LinkedHashMap<>();
        for (int y = 0; y < tileSize; y++) {
            for (int x = 0; x < tileSize; x++) {
                long k = keyAt(y, x, s0, precinct);
                int c = cursor.merge(k, 1, Integer::sum) - 1;
                buckets.get(k)[c] = coeff[y * tileSize + x];
            }
        }
        return buckets;
    }

    /**
     * Coloca de vuelta los valores decodificados (por bucket) en el arreglo de
     * coeficientes del tile, recorriendo en el mismo orden fila-mayor.
     */
    public static void scatter(Map<Long, int[]> decodedByKey, int[] coeff,
                               int tileSize, int levels, int precinct) {
        int s0 = tileSize >> levels;
        Map<Long, Integer> cursor = new LinkedHashMap<>();
        for (int y = 0; y < tileSize; y++) {
            for (int x = 0; x < tileSize; x++) {
                long k = keyAt(y, x, s0, precinct);
                int[] vals = decodedByKey.get(k);
                if (vals == null) {
                    continue; // bucket no recibido: se queda en cero
                }
                int c = cursor.merge(k, 1, Integer::sum) - 1;
                coeff[y * tileSize + x] = vals[c];
            }
        }
    }

    private static long keyAt(int y, int x, int s0, int precinct) {
        int level = resolutionLevel(y, x, s0);
        return key(level, y / precinct, x / precinct);
    }
}
