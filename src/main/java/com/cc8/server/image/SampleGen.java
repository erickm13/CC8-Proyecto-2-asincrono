package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Genera una imagen de prueba con detalle variado. Uso: SampleGen <png> [w] [h] */
public final class SampleGen {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "images/sample.png";
        int w = args.length > 1 ? Integer.parseInt(args[1]) : 1200;
        int h = args.length > 2 ? Integer.parseInt(args[2]) : 800;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        // Fondo con degradado + patron de alta frecuencia + circulos.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / w;
                int g = (y * 255) / h;
                int b = 128 + (int) (60 * Math.sin(x * 0.15) * Math.cos(y * 0.15));
                img.setRGB(x, y, (r << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        Graphics2D gfx = img.createGraphics();
        for (int i = 0; i < 40; i++) {
            gfx.setColor(new Color((i * 37) % 256, (i * 91) % 256, (i * 53) % 256));
            int s = 20 + (i * 13) % 200;
            gfx.fillOval((i * 97) % w, (i * 131) % h, s, s);
        }
        gfx.dispose();

        new File(out).getParentFile().mkdirs();
        ImageIO.write(img, "png", new File(out));
        System.out.printf("Generada %s (%dx%d)%n", out, w, h);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
