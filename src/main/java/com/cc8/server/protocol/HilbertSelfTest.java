package com.cc8.server.protocol;

/** Verifica biyeccion y localidad (pasos de vecino) de la curva de Hilbert. */
public final class HilbertSelfTest {
    public static void main(String[] args) {
        for (int n : new int[]{2, 4, 8, 16, 32}) {
            boolean[] seen = new boolean[n * n];
            int[] prev = null;
            int[] out = new int[2];
            for (long d = 0; d < (long) n * n; d++) {
                HilbertCurve.d2xy(n, d, out);
                int x = out[0], y = out[1];

                // Rango valido
                if (x < 0 || x >= n || y < 0 || y >= n) {
                    System.out.printf("FALLO n=%d d=%d fuera de rango (%d,%d)%n", n, d, x, y);
                    System.exit(1);
                }
                // Biyeccion: cada celda una sola vez
                int idx = y * n + x;
                if (seen[idx]) {
                    System.out.printf("FALLO n=%d celda repetida (%d,%d)%n", n, x, y);
                    System.exit(1);
                }
                seen[idx] = true;
                // Ida y vuelta d -> (x,y) -> d
                if (HilbertCurve.xy2d(n, x, y) != d) {
                    System.out.printf("FALLO n=%d xy2d != d en (%d,%d)%n", n, x, y);
                    System.exit(1);
                }
                // Localidad: paso de vecino (distancia Manhattan 1)
                if (prev != null) {
                    int man = Math.abs(x - prev[0]) + Math.abs(y - prev[1]);
                    if (man != 1) {
                        System.out.printf("FALLO n=%d salto no-vecino en d=%d (man=%d)%n", n, d, man);
                        System.exit(1);
                    }
                }
                prev = new int[]{x, y};
            }
            System.out.printf("OK  n=%-3d biyeccion + localidad (%d celdas)%n", n, n * n);
        }
        System.out.println("Todas las pruebas de Hilbert pasaron.");
    }
}
