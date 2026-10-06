package com.cc8.server.image;

/**
 * Codificacion de coeficientes wavelet (con signo) por planos de bits, con
 * signo perezoso: el bit de signo se emite en el plano en que el coeficiente
 * se vuelve significativo por primera vez (igual que el modelo de JPEG2000).
 *
 * <p>Cada plano de bits, del mas significativo (MSB) al menos significativo,
 * es una "quality layer": recibir mas planos = mas detalle. El plano 0 (layer
 * 0) es el MSB. La reconstruccion es exacta al recibir todos los planos.
 */
public final class BitPlaneCoder {

    private BitPlaneCoder() {
    }

    /** Numero de planos de bits necesarios para la magnitud maxima dada. */
    public static int numPlanes(int[] coeffs) {
        int maxMag = 0;
        for (int c : coeffs) {
            int m = Math.abs(c);
            if (m > maxMag) {
                maxMag = m;
            }
        }
        return maxMag == 0 ? 0 : (32 - Integer.numberOfLeadingZeros(maxMag));
    }

    /**
     * Codifica los coeficientes en {@code numPlanes} paquetes (uno por layer).
     * El paquete de la capa L corresponde al plano {@code numPlanes-1-L}.
     * Cada paquete ya viene comprimido con DEFLATE.
     */
    public static byte[][] encode(int[] coeffs, int numPlanes) {
        int n = coeffs.length;
        boolean[] significant = new boolean[n];
        byte[][] layers = new byte[numPlanes][];

        for (int layer = 0; layer < numPlanes; layer++) {
            int plane = numPlanes - 1 - layer;
            Bits.Writer bw = new Bits.Writer();
            for (int i = 0; i < n; i++) {
                int mag = Math.abs(coeffs[i]);
                int bit = (mag >>> plane) & 1;
                bw.writeBit(bit);
                if (bit == 1 && !significant[i]) {
                    significant[i] = true;
                    bw.writeBit(coeffs[i] < 0 ? 1 : 0);
                }
            }
            layers[layer] = Zlib.deflate(bw.toBytes());
        }
        return layers;
    }

    /**
     * Reconstruye los coeficientes a partir de los primeros
     * {@code receivedLayers} paquetes (reconstruccion progresiva). Los planos
     * no recibidos se asumen en cero.
     *
     * @param layers        paquetes DEFLATE por capa (algunos pueden ser null)
     * @param receivedLayers cuantas capas usar (prefijo)
     * @param n             numero de coeficientes del precinct
     * @param numPlanes     total de planos originales
     */
    public static int[] decode(byte[][] layers, int receivedLayers, int n, int numPlanes) {
        int[] mag = new int[n];
        boolean[] significant = new boolean[n];
        int[] sign = new int[n];

        for (int layer = 0; layer < receivedLayers; layer++) {
            if (layers[layer] == null) {
                break;
            }
            int plane = numPlanes - 1 - layer;
            byte[] raw = Zlib.inflate(layers[layer], n); // tamano aprox.
            Bits.Reader br = new Bits.Reader(raw);
            for (int i = 0; i < n; i++) {
                int bit = br.readBit();
                if (bit == 1) {
                    mag[i] |= (1 << plane);
                    if (!significant[i]) {
                        significant[i] = true;
                        sign[i] = br.readBit() == 1 ? -1 : 1;
                    }
                }
            }
        }

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = significant[i] ? sign[i] * mag[i] : 0;
        }
        return out;
    }
}
