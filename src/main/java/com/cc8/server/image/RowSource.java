package com.cc8.server.image;

import java.io.Closeable;
import java.io.IOException;

/**
 * Fuente de pixeles por franjas (strips) de escanlines. Permite alimentar al
 * preprocesador sin cargar la imagen completa en memoria: se procesa una franja
 * de {@code tileSize} filas a la vez. Los pixeles se entregan con
 * {@code components()} bytes por pixel (1 = gris, 3 = RGB).
 */
public interface RowSource extends Closeable {

    int width();

    int height();

    int components();

    /**
     * Llena hasta {@code rows} escanlines a partir de la posicion actual en
     * {@code strip} (layout: fila-mayor, {@code components} bytes por pixel).
     *
     * @return numero de filas realmente leidas (menos al final de la imagen)
     */
    int readStrip(byte[] strip, int rows) throws IOException;
}
