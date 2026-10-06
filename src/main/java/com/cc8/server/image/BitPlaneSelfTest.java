package com.cc8.server.image;

import java.util.Random;

/** Verifica codificacion/decodificacion por planos de bits. */
public final class BitPlaneSelfTest {
    public static void main(String[] args) {
        Random rnd = new Random(7);
        int n = 4096;
        int[] coeffs = new int[n];
        for (int i = 0; i < n; i++) {
            // Coeficientes tipo wavelet: muchos cercanos a cero, algunos grandes.
            int v = (int) (rnd.nextGaussian() * 40);
            coeffs[i] = v;
        }

        int numPlanes = BitPlaneCoder.numPlanes(coeffs);
        byte[][] layers = BitPlaneCoder.encode(coeffs, numPlanes);
        System.out.printf("n=%d, numPlanes=%d%n", n, numPlanes);

        // 1) Reconstruccion exacta con todas las capas.
        int[] full = BitPlaneCoder.decode(layers, numPlanes, n, numPlanes);
        for (int i = 0; i < n; i++) {
            if (full[i] != coeffs[i]) {
                System.out.printf("FALLO exacto en i=%d (%d != %d)%n", i, full[i], coeffs[i]);
                System.exit(1);
            }
        }
        System.out.println("OK  reconstruccion exacta con todas las capas");

        // 2) Reconstruccion progresiva: el error debe decrecer al agregar capas.
        long prevErr = Long.MAX_VALUE;
        for (int k = 1; k <= numPlanes; k++) {
            int[] approx = BitPlaneCoder.decode(layers, k, n, numPlanes);
            long err = 0;
            for (int i = 0; i < n; i++) {
                long d = approx[i] - coeffs[i];
                err += d * d;
            }
            System.out.printf("  capas=%2d/%d  SSE=%d%n", k, numPlanes, err);
            if (err > prevErr) {
                System.out.println("FALLO: el error aumento al agregar una capa");
                System.exit(1);
            }
            prevErr = err;
        }
        if (prevErr != 0) {
            System.out.println("FALLO: el error final no es cero");
            System.exit(1);
        }
        System.out.println("OK  el error decrece monotonicamente hasta 0");

        // 3) Tamano comprimido total vs crudo.
        int total = 0;
        for (byte[] l : layers) total += l.length;
        System.out.printf("Tamano capas comprimidas: %d bytes (crudo int: %d bytes)%n",
                total, n * 4);
        System.out.println("Todas las pruebas de bit-planes pasaron.");
    }
}
