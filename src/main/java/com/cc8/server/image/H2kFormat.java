package com.cc8.server.image;

import java.util.List;

/**
 * Constantes y estructuras del formato en disco .h2k (Hilbert-ordered
 * Progressive, 2K = inspirado en JPEG2000).
 *
 * <p>Layout del archivo:
 * <pre>
 *   [Header 64 bytes]
 *   [Region de paquetes: todos los paquetes (plano de bits) DEFLATE]
 *   [Bloques de indice por tile, intercalados con sus paquetes]
 *   [Directorio de tiles: (offset,len) del bloque de indice de cada tile]
 * </pre>
 * El header apunta al directorio; cada tile se indexa por separado para que el
 * servidor solo lea el indice del tile que necesita (imagenes de 50 GB+ no
 * caben en RAM ni su indice completo hace falta de una vez).
 */
public final class H2kFormat {

    public static final byte[] MAGIC = {'H', '2', 'K', '1'};
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 64;
    public static final int TILEDIR_OFFSET_FIELD = 36; // long: offset al directorio de tiles
    // Vista general (overview / thumbnail) en el espacio reservado del header:
    public static final int OVERVIEW_OFFSET_FIELD = 44; // long: offset al PNG de overview
    public static final int OVERVIEW_LEN_FIELD = 52;    // int: longitud del PNG
    public static final int OVERVIEW_W_FIELD = 56;      // int: ancho del overview
    public static final int OVERVIEW_H_FIELD = 60;      // int: alto del overview

    // Parametros por defecto del preprocesamiento.
    public static final int DEFAULT_TILE = 512;
    public static final int DEFAULT_LEVELS = 5;
    public static final int DEFAULT_PRECINCT = 64;
    public static final int LEVEL_SHIFT = 128; // centrado de muestras de 8 bits

    private H2kFormat() {
    }

    /** Cabecera global de la imagen. */
    public record Header(int version, int colorTransform, int components,
                         int bitDepth, int tileSize, int levels, int precinct,
                         int width, int height, int tilesX, int tilesY,
                         long tileDirOffset,
                         long overviewOffset, int overviewLen,
                         int overviewW, int overviewH) {

        /** Factor de escala del overview respecto a la imagen (= 2^levels). */
        public int overviewScale() {
            return tileSize / (tileSize >> levels);
        }
    }

    /** Indice de un precinct: metadatos + ubicacion de cada capa (plano). */
    public record PacketIndex(int level, int py, int px, int numCoeffs,
                              int numPlanes, long[] layerOffset, int[] layerLen) {
    }

    /** Indice de un tile: region valida + precincts por componente. */
    public record TileIndex(int validW, int validH,
                            List<List<PacketIndex>> components) {
    }
}
