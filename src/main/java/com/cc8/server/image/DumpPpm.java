package com.cc8.server.image;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vuelca la reconstruccion completa de un .h2k a un PPM (P6) crudo, facil de
 * leer desde otros lenguajes. Se usa como verdad de referencia para verificar
 * el port de decodificacion en JavaScript. Uso: DumpPpm <in.h2k> <out.ppm>
 */
public final class DumpPpm {
    public static void main(String[] args) throws Exception {
        try (H2kReader reader = new H2kReader(Path.of(args[0]))) {
            BufferedImage img = Decoder.reconstructFull(reader, Integer.MAX_VALUE);
            int w = img.getWidth(), h = img.getHeight();
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(Path.of(args[1])))) {
                out.write(("P6\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII));
                byte[] row = new byte[w * 3];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int rgb = img.getRGB(x, y);
                        row[x * 3] = (byte) ((rgb >> 16) & 0xFF);
                        row[x * 3 + 1] = (byte) ((rgb >> 8) & 0xFF);
                        row[x * 3 + 2] = (byte) (rgb & 0xFF);
                    }
                    out.write(row);
                }
            }
            System.out.printf("PPM %dx%d -> %s%n", w, h, args[1]);
        }
    }
}
