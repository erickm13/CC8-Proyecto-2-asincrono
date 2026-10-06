package com.cc8.server.image;

import java.awt.image.BufferedImage;

/**
 * Adapta un {@link BufferedImage} en memoria a {@link RowSource}, para reutilizar
 * el mismo camino de preprocesamiento por franjas con imagenes pequenas o de
 * otros formatos leidos por ImageIO.
 */
public final class BufferedImageRowSource implements RowSource {

    private final BufferedImage img;
    private final int components;
    private int y = 0;

    public BufferedImageRowSource(BufferedImage img) {
        this.img = img;
        this.components = img.getColorModel().getNumComponents() == 1 ? 1 : 3;
    }

    @Override public int width() { return img.getWidth(); }
    @Override public int height() { return img.getHeight(); }
    @Override public int components() { return components; }

    @Override
    public int readStrip(byte[] strip, int rows) {
        int w = img.getWidth(), h = img.getHeight();
        int filled = 0;
        for (int r = 0; r < rows && y < h; r++, y++) {
            int base = r * w * components;
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                if (components == 3) {
                    strip[base + x * 3] = (byte) ((rgb >> 16) & 0xFF);
                    strip[base + x * 3 + 1] = (byte) ((rgb >> 8) & 0xFF);
                    strip[base + x * 3 + 2] = (byte) (rgb & 0xFF);
                } else {
                    strip[base + x] = (byte) (rgb & 0xFF);
                }
            }
            filled++;
        }
        return filled;
    }

    @Override public void close() { }
}
