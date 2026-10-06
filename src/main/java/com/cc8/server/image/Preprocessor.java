package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * CLI de preprocesamiento: imagen -> archivo .h2k.
 *
 * Uso: java ... Preprocessor <entrada> <salida.h2k> [tile] [levels] [precinct]
 *
 * <p>Los PNG se leen por STREAMING (fila por fila), asi que soporta PNGs de
 * decenas de GB sin cargarlos en RAM. Otros formatos se leen con ImageIO en
 * memoria (solo apto para imagenes pequenas).
 */
public final class Preprocessor {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: Preprocessor <entrada> <salida.h2k> [tile] [levels] [precinct]");
            System.exit(2);
        }
        int tile = args.length > 2 ? Integer.parseInt(args[2]) : H2kFormat.DEFAULT_TILE;
        int levels = args.length > 3 ? Integer.parseInt(args[3]) : H2kFormat.DEFAULT_LEVELS;
        int precinct = args.length > 4 ? Integer.parseInt(args[4]) : H2kFormat.DEFAULT_PRECINCT;

        RowSource src;
        if (args[0].toLowerCase().endsWith(".png")) {
            src = new PngStreamReader(Path.of(args[0]));   // streaming, apto para GB
        } else {
            BufferedImage img = ImageIO.read(new File(args[0]));
            if (img == null) {
                System.err.println("No se pudo leer la imagen: " + args[0]);
                System.exit(1);
                return;
            }
            src = new BufferedImageRowSource(img);
        }

        int w = src.width(), h = src.height();
        long t0 = System.currentTimeMillis();
        new H2kWriter(tile, levels, precinct).write(src, Path.of(args[1]));
        long ms = System.currentTimeMillis() - t0;

        File out = new File(args[1]);
        System.out.printf("OK  %dx%d -> %s  (%.1f MB, %.1f s, tile=%d levels=%d precinct=%d)%n",
                w, h, args[1], out.length() / 1e6, ms / 1000.0, tile, levels, precinct);
    }
}
