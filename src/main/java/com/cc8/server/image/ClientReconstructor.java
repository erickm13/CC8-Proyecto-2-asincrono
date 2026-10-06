package com.cc8.server.image;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Reconstruccion del lado del cliente a partir de los paquetes recibidos (no
 * del archivo). Es la referencia del algoritmo que correra en JavaScript en el
 * navegador: decodifica los planos de bits recibidos, dispersa los coeficientes
 * y aplica la DWT inversa. Con todas las capas, la reconstruccion es exacta.
 */
public final class ClientReconstructor {

    /** Precinct recibido: metadatos + capas (algunas pueden faltar). */
    public record Precinct(int level, int py, int px, int numCoeffs, int numPlanes,
                           byte[][] layers, int received) {
    }

    private ClientReconstructor() {
    }

    /** Reconstruye un componente (tileSize x tileSize) de un tile. Pixeles 0..255. */
    public static int[] component(H2kFormat.Header h, Collection<Precinct> precincts) {
        Map<Long, int[]> decodedByKey = new HashMap<>();
        for (Precinct p : precincts) {
            int[] vals = BitPlaneCoder.decode(p.layers(), p.received(), p.numCoeffs(), p.numPlanes());
            decodedByKey.put(Precincts.key(p.level(), p.py(), p.px()), vals);
        }
        int ts = h.tileSize();
        int[] coeff = new int[ts * ts];
        Precincts.scatter(decodedByKey, coeff, ts, h.levels(), h.precinct());
        HaarWavelet.inverse2D(coeff, ts, h.levels());
        for (int i = 0; i < coeff.length; i++) {
            int v = coeff[i] + H2kFormat.LEVEL_SHIFT;
            coeff[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
        return coeff;
    }
}
