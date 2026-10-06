package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * Vuelca un tile individual de un .h2k a PNG, a un presupuesto de capas dado
 * (para verificar nitidez / calidad progresiva sin reensamblar toda la imagen).
 *
 * Uso: TilePng <archivo.h2k> <tile> <salida.png> [layerBudget]
 */
public final class TilePng {
    public static void main(String[] args) throws Exception {
        int layerBudget = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;
        try (H2kReader r = new H2kReader(Path.of(args[0]))) {
            int tile = Integer.parseInt(args[1]);
            H2kFormat.Header h = r.header();
            H2kFormat.TileIndex idx = r.readTileIndex(tile);
            int ts = h.tileSize();
            int[] rr = r.reconstructComponent(idx, 0, layerBudget);
            int[] gg = h.components() == 1 ? rr : r.reconstructComponent(idx, 1, layerBudget);
            int[] bb = h.components() == 1 ? rr : r.reconstructComponent(idx, 2, layerBudget);

            int vw = idx.validW(), vh = idx.validH();
            BufferedImage img = new BufferedImage(vw, vh, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < vh; y++) {
                for (int x = 0; x < vw; x++) {
                    int p = y * ts + x;
                    img.setRGB(x, y, (rr[p] << 16) | (gg[p] << 8) | bb[p]);
                }
            }
            ImageIO.write(img, "png", new File(args[2]));
            int tx = tile % h.tilesX(), ty = tile / h.tilesX();
            System.out.printf("tile %d (tx=%d,ty=%d) -> %s  (%dx%d, px imagen ~[%d,%d])%n",
                    tile, tx, ty, args[2], vw, vh, tx * ts, ty * ts);
        }
    }
}
