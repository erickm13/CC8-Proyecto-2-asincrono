package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * CLI de depuracion: reconstruye un .h2k a PNG usando un presupuesto de capas
 * por precinct (para "ver" la calidad progresiva). Reensambla todos los tiles,
 * asi que conviene solo para imagenes moderadas / pruebas.
 *
 * Uso: java ... Decoder <archivo.h2k> <salida.png> [layerBudget]
 */
public final class Decoder {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: Decoder <archivo.h2k> <salida.png> [layerBudget]");
            System.exit(2);
        }
        int layerBudget = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;

        try (H2kReader reader = new H2kReader(Path.of(args[0]))) {
            BufferedImage img = reconstructFull(reader, layerBudget);
            ImageIO.write(img, "png", new File(args[1]));
            H2kFormat.Header h = reader.header();
            System.out.printf("OK  %dx%d  comps=%d  layerBudget=%s -> %s%n",
                    h.width(), h.height(), h.components(),
                    layerBudget == Integer.MAX_VALUE ? "MAX" : layerBudget, args[1]);
        }
    }

    /** Reensambla la imagen completa (uso: pruebas / imagenes pequenas). */
    public static BufferedImage reconstructFull(H2kReader reader, int layerBudget) throws Exception {
        H2kFormat.Header h = reader.header();
        BufferedImage img = new BufferedImage(h.width(), h.height(), BufferedImage.TYPE_INT_RGB);
        int ts = h.tileSize();

        for (int ty = 0; ty < h.tilesY(); ty++) {
            for (int tx = 0; tx < h.tilesX(); tx++) {
                int t = ty * h.tilesX() + tx;
                H2kFormat.TileIndex idx = reader.readTileIndex(t);
                int[] r = reader.reconstructComponent(idx, 0, layerBudget);
                int[] g = h.components() == 1 ? r : reader.reconstructComponent(idx, 1, layerBudget);
                int[] b = h.components() == 1 ? r : reader.reconstructComponent(idx, 2, layerBudget);

                int x0 = tx * ts, y0 = ty * ts;
                for (int yy = 0; yy < idx.validH(); yy++) {
                    for (int xx = 0; xx < idx.validW(); xx++) {
                        int p = yy * ts + xx;
                        int rgb = (r[p] << 16) | (g[p] << 8) | b[p];
                        img.setRGB(x0 + xx, y0 + yy, rgb);
                    }
                }
            }
        }
        return img;
    }
}
