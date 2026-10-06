package com.cc8.server.image;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lee un archivo .h2k con acceso aleatorio posicional (sin cargar la imagen
 * completa; adecuado para archivos de 50 GB+). Ofrece:
 * <ul>
 *   <li>Lectura del header y del indice de un tile bajo demanda.</li>
 *   <li>Lectura de un paquete (plano de bits) individual -> lo usa el servidor
 *       para transmitir capas selectivamente.</li>
 *   <li>Reconstruccion progresiva de un componente con un presupuesto de capas
 *       (referencia del algoritmo que corre tambien en el cliente).</li>
 * </ul>
 */
public final class H2kReader implements Closeable {

    private final FileChannel channel;
    private final H2kFormat.Header header;
    private final long[] tileIdxOffset;
    private final int[] tileIdxLen;

    public H2kReader(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);

        byte[] head = readAt(0, H2kFormat.HEADER_SIZE);
        if (head[0] != 'H' || head[1] != '2' || head[2] != 'K' || head[3] != '1') {
            throw new IOException("Archivo .h2k invalido (magic)");
        }
        int components = head[6] & 0xFF;
        int bitDepth = head[7] & 0xFF;
        int tileSize = getInt(head, 8);
        int levels = getInt(head, 12);
        int precinct = getInt(head, 16);
        int width = getInt(head, 20);
        int height = getInt(head, 24);
        int tilesX = getInt(head, 28);
        int tilesY = getInt(head, 32);
        long tileDirOffset = getLong(head, 36);
        this.header = new H2kFormat.Header(head[4] & 0xFF, head[5] & 0xFF, components,
                bitDepth, tileSize, levels, precinct, width, height,
                tilesX, tilesY, tileDirOffset);

        int numTiles = tilesX * tilesY;
        this.tileIdxOffset = new long[numTiles];
        this.tileIdxLen = new int[numTiles];
        byte[] dir = readAt(tileDirOffset, numTiles * 12);
        for (int t = 0; t < numTiles; t++) {
            tileIdxOffset[t] = getLong(dir, t * 12);
            tileIdxLen[t] = getInt(dir, t * 12 + 8);
        }
    }

    public H2kFormat.Header header() {
        return header;
    }

    public int numTiles() {
        return header.tilesX() * header.tilesY();
    }

    /** Lee y parsea el indice de un tile bajo demanda. */
    public H2kFormat.TileIndex readTileIndex(int tile) throws IOException {
        byte[] buf = readAt(tileIdxOffset[tile], tileIdxLen[tile]);
        int[] pos = {0};
        int validW = getInt(buf, advance(pos, 4));
        int validH = getInt(buf, advance(pos, 4));
        List<List<H2kFormat.PacketIndex>> comps = new ArrayList<>();
        for (int c = 0; c < header.components(); c++) {
            int numPrecincts = getInt(buf, advance(pos, 4));
            List<H2kFormat.PacketIndex> list = new ArrayList<>(numPrecincts);
            for (int p = 0; p < numPrecincts; p++) {
                int level = buf[advance(pos, 1)] & 0xFF;
                int py = getShort(buf, advance(pos, 2));
                int px = getShort(buf, advance(pos, 2));
                int numCoeffs = getInt(buf, advance(pos, 4));
                int numPlanes = buf[advance(pos, 1)] & 0xFF;
                long[] offs = new long[numPlanes];
                int[] lens = new int[numPlanes];
                for (int l = 0; l < numPlanes; l++) {
                    offs[l] = getLong(buf, advance(pos, 8));
                    lens[l] = getInt(buf, advance(pos, 4));
                }
                list.add(new H2kFormat.PacketIndex(level, py, px, numCoeffs,
                        numPlanes, offs, lens));
            }
            comps.add(list);
        }
        return new H2kFormat.TileIndex(validW, validH, comps);
    }

    /** Lee los bytes DEFLATE de un paquete (plano) concreto. */
    public byte[] readPacket(H2kFormat.PacketIndex p, int layer) throws IOException {
        return readAt(p.layerOffset()[layer], p.layerLen()[layer]);
    }

    /**
     * Reconstruye un componente de un tile usando hasta {@code layerBudget}
     * capas por precinct (reconstruccion progresiva). Devuelve pixeles 0..255
     * de tamano tileSize x tileSize (solo la region valida es significativa).
     */
    public int[] reconstructComponent(int tile, int comp, int layerBudget) throws IOException {
        H2kFormat.TileIndex idx = readTileIndex(tile);
        return reconstructComponent(idx, comp, layerBudget);
    }

    public int[] reconstructComponent(H2kFormat.TileIndex idx, int comp,
                                      int layerBudget) throws IOException {
        int tileSize = header.tileSize();
        Map<Long, int[]> decodedByKey = new HashMap<>();

        for (H2kFormat.PacketIndex p : idx.components().get(comp)) {
            long key = Precincts.key(p.level(), p.py(), p.px());
            int received = Math.min(layerBudget, p.numPlanes());
            byte[][] layers = new byte[p.numPlanes()][];
            for (int l = 0; l < received; l++) {
                layers[l] = readPacket(p, l);
            }
            int[] values = BitPlaneCoder.decode(layers, received, p.numCoeffs(), p.numPlanes());
            decodedByKey.put(key, values);
        }

        int[] coeff = new int[tileSize * tileSize];
        Precincts.scatter(decodedByKey, coeff, tileSize, header.levels(), header.precinct());
        HaarWavelet.inverse2D(coeff, tileSize, header.levels());

        for (int i = 0; i < coeff.length; i++) {
            int v = coeff[i] + H2kFormat.LEVEL_SHIFT;
            coeff[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
        return coeff;
    }

    // ---- Utilidades de lectura -------------------------------------------

    private byte[] readAt(long offset, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        int read = 0;
        while (read < len) {
            int n = channel.read(buf, offset + read);
            if (n < 0) {
                throw new IOException("EOF inesperado en offset " + offset);
            }
            read += n;
        }
        return buf.array();
    }

    private static int advance(int[] pos, int n) {
        int p = pos[0];
        pos[0] += n;
        return p;
    }

    private static int getShort(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int getInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static long getLong(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[o + i] & 0xFF);
        }
        return v;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
