package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI de preprocesamiento: imagen -> archivo .h2k.
 *
 * Uso: java ... Preprocessor <entrada> <salida.h2k> [tile] [levels] [precinct]
 *      [--compression-level=0..9] [--threads=N]
 *
 * <p>Los PNG se leen por STREAMING (fila por fila), asi que soporta PNGs de
 * decenas de GB sin cargarlos en RAM. Otros formatos se leen con ImageIO en
 * memoria (solo apto para imagenes pequenas).
 */
public final class Preprocessor {

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>(5);
        int compressionLevel = 6;
        int threads = H2kWriter.DEFAULT_THREADS;
        boolean compressionLevelSet = false;
        boolean threadsSet = false;
        for (String arg : args) {
            if (arg.startsWith("--compression-level=")) {
                if (compressionLevelSet) {
                    printUsage();
                    System.exit(2);
                    return;
                }
                compressionLevel = Integer.parseInt(arg.substring("--compression-level=".length()));
                Zlib.checkLevel(compressionLevel);
                compressionLevelSet = true;
            } else if (arg.startsWith("--threads=")) {
                if (threadsSet) {
                    printUsage();
                    System.exit(2);
                    return;
                }
                threads = Integer.parseInt(arg.substring("--threads=".length()));
                if (threads < 1) {
                    printUsage();
                    System.exit(2);
                    return;
                }
                threadsSet = true;
            } else if (arg.startsWith("--")) {
                printUsage();
                System.exit(2);
                return;
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() < 2 || positional.size() > 5) {
            printUsage();
            System.exit(2);
            return;
        }
        String input = positional.get(0);
        String output = positional.get(1);
        int tile = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : H2kFormat.DEFAULT_TILE;
        int levels = positional.size() > 3 ? Integer.parseInt(positional.get(3)) : H2kFormat.DEFAULT_LEVELS;
        int precinct = positional.size() > 4 ? Integer.parseInt(positional.get(4)) : H2kFormat.DEFAULT_PRECINCT;

        RowSource src;
        if (input.toLowerCase().endsWith(".png")) {
            src = new PngStreamReader(Path.of(input));   // streaming, apto para GB
        } else {
            BufferedImage img = ImageIO.read(new File(input));
            if (img == null) {
                System.err.println("No se pudo leer la imagen: " + input);
                System.exit(1);
                return;
            }
            src = new BufferedImageRowSource(img);
        }

        int w = src.width(), h = src.height();
        H2kWriter.Metrics metrics = new H2kWriter(tile, levels, precinct, compressionLevel, threads)
                .writeWithMetrics(src, Path.of(output));

        File out = new File(output);
        System.out.printf("OK  %dx%d -> %s  (%.1f MB, total=%.3f s, read=%.3f s, transform/encode=%.3f s, output=%.3f s, tile=%d levels=%d precinct=%d compression=%d threads=%d)%n",
                w, h, output, out.length() / 1e6,
                metrics.elapsedNanos() / 1e9, metrics.readNanos() / 1e9,
                metrics.transformEncodeNanos() / 1e9, metrics.outputWriteNanos() / 1e9,
                tile, levels, precinct, compressionLevel, threads);
    }

    private static void printUsage() {
        System.err.println("Uso: Preprocessor <entrada> <salida.h2k> [tile] [levels] [precinct] [--compression-level=0..9] [--threads=N]");
    }
}
