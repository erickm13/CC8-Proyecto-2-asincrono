package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifica el decodificador PNG por streaming: preprocesa un PNG por streaming y
 * por ImageIO, y comprueba que ambos .h2k son IDENTICOS byte a byte (misma
 * decodificacion de pixeles), ademas de que la reconstruccion es sin perdida.
 *
 * Uso: PngIngestTest <imagen.png>
 */
public final class PngIngestTest {

    public static void main(String[] args) throws Exception {
        String png = args.length > 0 ? args[0] : "/tmp/cc8imgs/000-005-300-11843.png";

        Path a = Files.createTempFile("stream", ".h2k");
        Path b = Files.createTempFile("imageio", ".h2k");
        try {
            // Streaming
            new H2kWriter(256, 4, 64).write(new PngStreamReader(Path.of(png)), a);
            // ImageIO en memoria
            BufferedImage img = ImageIO.read(new File(png));
            new H2kWriter(256, 4, 64).write(new BufferedImageRowSource(img), b);

            byte[] da = Files.readAllBytes(a);
            byte[] db = Files.readAllBytes(b);
            if (da.length != db.length || !java.util.Arrays.equals(da, db)) {
                System.out.printf("FALLO: .h2k difieren (streaming=%d, imageio=%d bytes)%n",
                        da.length, db.length);
                System.exit(1);
            }
            System.out.printf("OK  streaming == ImageIO byte a byte (%d bytes)%n", da.length);

            // Losslessness contra el PNG original.
            try (H2kReader reader = new H2kReader(a)) {
                BufferedImage rec = Decoder.reconstructFull(reader, Integer.MAX_VALUE);
                long mism = 0;
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        if ((img.getRGB(x, y) & 0xFFFFFF) != (rec.getRGB(x, y) & 0xFFFFFF)) mism++;
                    }
                }
                if (mism != 0) {
                    System.out.println("FALLO: reconstruccion con " + mism + " pixeles distintos");
                    System.exit(1);
                }
                System.out.printf("OK  reconstruccion sin perdida (%dx%d)%n",
                        img.getWidth(), img.getHeight());
            }
            System.out.println("PngIngestTest paso.");
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
        }
    }
}
