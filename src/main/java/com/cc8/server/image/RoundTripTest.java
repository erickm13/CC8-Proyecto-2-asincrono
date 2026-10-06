package com.cc8.server.image;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prueba integral del formato .h2k: genera una imagen, la preprocesa, la
 * reconstruye y verifica que con todas las capas es identica pixel a pixel
 * (sin perdida), y que con menos capas el error total es menor.
 */
public final class RoundTripTest {

    public static void main(String[] args) throws Exception {
        int w = 300, h = 175; // dimensiones NO multiplos del tile: prueba bordes
        BufferedImage src = synthetic(w, h);

        Path tmp = Files.createTempFile("roundtrip", ".h2k");
        // tile pequeno para forzar varios tiles con esta imagen chica
        new H2kWriter(64, 4, 16).write(src, tmp);
        System.out.printf("Archivo .h2k: %d bytes%n", Files.size(tmp));

        try (H2kReader reader = new H2kReader(tmp)) {
            // 1) Reconstruccion completa == original (sin perdida)
            BufferedImage full = Decoder.reconstructFull(reader, Integer.MAX_VALUE);
            long mismatches = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((src.getRGB(x, y) & 0xFFFFFF) != (full.getRGB(x, y) & 0xFFFFFF)) {
                        mismatches++;
                    }
                }
            }
            if (mismatches != 0) {
                System.out.printf("FALLO: %d pixeles distintos en reconstruccion completa%n", mismatches);
                System.exit(1);
            }
            System.out.println("OK  reconstruccion completa identica (sin perdida)");

            // 2) Error decreciente al aumentar el presupuesto de capas
            long prev = Long.MAX_VALUE;
            for (int budget : new int[]{1, 2, 4, 8, 16}) {
                BufferedImage approx = Decoder.reconstructFull(reader, budget);
                long sse = sse(src, approx, w, h);
                System.out.printf("  layerBudget=%2d  SSE=%d%n", budget, sse);
                if (sse > prev) {
                    System.out.println("FALLO: el error aumento con mas capas");
                    System.exit(1);
                }
                prev = sse;
            }
            System.out.println("OK  el error decrece al aumentar capas");
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("RoundTripTest paso.");
    }

    private static long sse(BufferedImage a, BufferedImage b, int w, int h) {
        long sse = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                for (int s = 0; s < 24; s += 8) {
                    int d = ((pa >> s) & 0xFF) - ((pb >> s) & 0xFF);
                    sse += (long) d * d;
                }
            }
        }
        return sse;
    }

    private static BufferedImage synthetic(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / w;
                int g = (y * 255) / h;
                int b = ((x + y) * 127 / (w + h)) + (((x / 8 + y / 8) % 2) * 64); // textura
                b = Math.min(255, b);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }
}
