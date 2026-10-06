package com.cc8.server.image;

import java.util.Random;

/** Prueba de reversibilidad de {@link HaarWavelet}. Uso: java ... DwtSelfTest */
public final class DwtSelfTest {
    public static void main(String[] args) {
        int[] sizes = {8, 16, 512};
        int[] levelsArr = {1, 3, 5};
        Random rnd = new Random(42);

        for (int size : sizes) {
            for (int levels : levelsArr) {
                if ((size >> levels) < 1) continue;
                int[] original = new int[size * size];
                for (int i = 0; i < original.length; i++) {
                    original[i] = rnd.nextInt(256); // valores de pixel 0..255
                }
                int[] work = original.clone();
                HaarWavelet.forward2D(work, size, levels);
                HaarWavelet.inverse2D(work, size, levels);

                for (int i = 0; i < original.length; i++) {
                    if (original[i] != work[i]) {
                        System.out.printf("FALLO size=%d levels=%d en i=%d (%d != %d)%n",
                                size, levels, i, original[i], work[i]);
                        System.exit(1);
                    }
                }
                System.out.printf("OK  size=%-4d levels=%d  reversible exacto%n", size, levels);
            }
        }
        System.out.println("Todas las pruebas de DWT pasaron.");
    }
}
