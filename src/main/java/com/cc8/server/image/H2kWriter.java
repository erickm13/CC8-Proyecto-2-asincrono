package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Preprocesa una imagen a formato .h2k procesando por FRANJAS (strips) de
 * {@code tileSize} filas tomadas de un {@link RowSource}. Nunca carga la imagen
 * completa: solo una franja a la vez (apto para PNGs de decenas de GB). Por cada
 * tile aplica DWT Haar reversible, reparte en (nivel, precinct) y codifica cada
 * precinct por planos de bits comprimidos con DEFLATE.
 */
public final class H2kWriter {

    public static final int DEFAULT_THREADS = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors()));

    private final int tileSize;
    private final int levels;
    private final int precinct;
    private final int compressionLevel;
    private final int threads;

    /**
     * Duraciones en nanosegundos. readNanos mide RowSource.readStrip;
     * outputWriteNanos mide las escrituras, índices, flush y parche del header.
     * transformEncodeNanos suma tiempos de workers (puede superar elapsedNanos)
     * e incluye la codificación del PNG de overview.
     */
    public record Metrics(long readNanos, long transformEncodeNanos,
                          long outputWriteNanos, long elapsedNanos) { }

    public H2kWriter(int tileSize, int levels, int precinct) {
        this(tileSize, levels, precinct, 6, DEFAULT_THREADS);
    }

    public H2kWriter(int tileSize, int levels, int precinct, int compressionLevel) {
        this(tileSize, levels, precinct, compressionLevel, DEFAULT_THREADS);
    }

    public H2kWriter(int tileSize, int levels, int precinct, int compressionLevel, int threads) {
        Zlib.checkLevel(compressionLevel);
        if (threads < 1) throw new IllegalArgumentException("threads debe ser >= 1");
        this.tileSize = tileSize;
        this.levels = levels;
        this.precinct = precinct;
        this.compressionLevel = compressionLevel;
        this.threads = threads;
    }

    private record TileResult(byte[] payload, List<List<H2kFormat.PacketIndex>> indices,
                              long encodeNanos) { }

    /** Cuenta bytes escritos para conocer offsets absolutos. */
    private static final class Counting extends FilterOutputStream {
        long count = 0;
        long writeNanos = 0;
        Counting(OutputStream out) {
            super(out);
        }
        @Override public void write(int b) throws IOException {
            long started = System.nanoTime();
            try {
                out.write(b);
                count++;
            } finally {
                writeNanos += System.nanoTime() - started;
            }
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            long started = System.nanoTime();
            try {
                out.write(b, off, len);
                count += len;
            } finally {
                writeNanos += System.nanoTime() - started;
            }
        }
        @Override public void flush() throws IOException {
            long started = System.nanoTime();
            try {
                out.flush();
            } finally {
                writeNanos += System.nanoTime() - started;
            }
        }
    }

    /** Camino en memoria (imagenes pequenas u otros formatos via ImageIO). */
    public void write(BufferedImage image, Path outputPath) throws IOException {
        write(new BufferedImageRowSource(image), outputPath);
    }

    /** Camino por streaming: procesa la imagen franja por franja. */
    public void write(RowSource src, Path outputPath) throws IOException {
        writeWithMetrics(src, outputPath);
    }

    /**
     * Camino por streaming que devuelve los tiempos acumulados de cada fase.
     */
    public Metrics writeWithMetrics(RowSource src, Path outputPath) throws IOException {
        long elapsedStarted = System.nanoTime();
        long readNanos = 0;
        long transformEncodeNanos = 0;
        long outputWriteNanos = 0;
        int width = src.width();
        int height = src.height();
        int components = src.components();

        int tilesX = (width + tileSize - 1) / tileSize;
        int tilesY = (height + tileSize - 1) / tileSize;
        int numTiles = tilesX * tilesY;

        long[] tileIdxOffset = new long[numTiles];
        int[] tileIdxLen = new int[numTiles];

        // Una franja de tileSize filas a ancho completo (unico buffer grande).
        byte[] strip = new byte[width * tileSize * components];

        // Overview (thumbnail): el bloque LL (s0 x s0) de cada tile. Resolucion
        // = imagen / 2^levels. Se arma en memoria y se guarda como PNG al final.
        int s0 = tileSize >> levels;
        int ovW = tilesX * s0;
        int ovH = tilesY * s0;
        byte[] overview = new byte[ovW * ovH * components];

        ExecutorService workers = threads == 1 ? null : Executors.newFixedThreadPool(threads);
        try (Counting counting = new Counting(
                     new BufferedOutputStream(Files.newOutputStream(outputPath), 1 << 20));
             DataOutputStream out = new DataOutputStream(counting)) {

            writeHeaderPlaceholder(out, components, width, height, tilesX, tilesY);

            for (int ty = 0; ty < tilesY; ty++) {
                final int tileY = ty;
                int y0 = ty * tileSize;
                int validH = Math.min(tileSize, height - y0);
                long readStarted = System.nanoTime();
                int got = src.readStrip(strip, validH);
                readNanos += System.nanoTime() - readStarted;
                if (got < validH) {
                    throw new IOException("Fuente truncada: faltan filas en la franja " + ty);
                }

                ArrayDeque<Future<TileResult>> pending = new ArrayDeque<>();
                for (int tx = 0; tx < tilesX; tx++) {
                    final int tileX = tx;
                    if (workers != null) {
                        pending.addLast(workers.submit(() -> encodeTile(strip, width, components,
                                validH, tileX, tileY, overview, ovW, s0)));
                    }
                    if (workers != null && pending.size() < threads) continue;
                    int outputTx = workers == null ? tx : tx - pending.size() + 1;
                    TileResult tile = workers == null
                            ? encodeTile(strip, width, components, validH, tx, ty, overview, ovW, s0)
                            : awaitTile(pending.removeFirst());
                    transformEncodeNanos += tile.encodeNanos();
                    long payloadOffset = counting.count;
                    out.write(tile.payload());
                    int t = ty * tilesX + outputTx;
                    int x0 = outputTx * tileSize;
                    int validW = Math.min(tileSize, width - x0);
                    long idxOffset = counting.count;
                    writeTileIndex(out, validW, validH, tile.indices(), payloadOffset);
                    tileIdxOffset[t] = idxOffset;
                    tileIdxLen[t] = (int) (counting.count - idxOffset);
                }
                while (!pending.isEmpty()) {
                    int outputTx = tilesX - pending.size();
                    TileResult tile = awaitTile(pending.removeFirst());
                    transformEncodeNanos += tile.encodeNanos();
                    long payloadOffset = counting.count;
                    out.write(tile.payload());
                    int t = ty * tilesX + outputTx;
                    int x0 = outputTx * tileSize;
                    int validW = Math.min(tileSize, width - x0);
                    long idxOffset = counting.count;
                    writeTileIndex(out, validW, validH, tile.indices(), payloadOffset);
                    tileIdxOffset[t] = idxOffset;
                    tileIdxLen[t] = (int) (counting.count - idxOffset);
                }
            }

            long dirOffset = counting.count;
            for (int t = 0; t < numTiles; t++) {
                out.writeLong(tileIdxOffset[t]);
                out.writeInt(tileIdxLen[t]);
            }

            // Overview al final del archivo, como PNG.
            long ovOffset = counting.count;
            long overviewEncodeStarted = System.nanoTime();
            byte[] png = encodeOverviewPng(overview, ovW, ovH, components);
            transformEncodeNanos += System.nanoTime() - overviewEncodeStarted;
            out.write(png);
            out.flush();

            long patchStarted = System.nanoTime();
            patchHeader(outputPath, dirOffset, ovOffset, png.length, ovW, ovH);
            counting.writeNanos += System.nanoTime() - patchStarted;
            outputWriteNanos = counting.writeNanos;
        } finally {
            if (workers != null) workers.shutdownNow();
            src.close();
        }
        return new Metrics(readNanos, transformEncodeNanos, outputWriteNanos,
                System.nanoTime() - elapsedStarted);
    }

    private static TileResult awaitTile(Future<TileResult> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido al codificar tile", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Error al codificar tile", cause);
        }
    }

    private TileResult encodeTile(byte[] strip, int width, int components, int validH,
                                  int tx, int ty, byte[] overview, int ovW, int s0) throws IOException {
        long started = System.nanoTime();
        int x0 = tx * tileSize;
        int validW = Math.min(tileSize, width - x0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Counting local = new Counting(bytes);
        DataOutputStream out = new DataOutputStream(local);
        List<List<H2kFormat.PacketIndex>> indices = new ArrayList<>();
        for (int c = 0; c < components; c++) {
            int[] coeff = extractFromStrip(strip, width, components, x0, validW, validH, c);
            HaarWavelet.forward2D(coeff, tileSize, levels);
            extractOverview(coeff, overview, ovW, components, s0, tx, ty, c);
            indices.add(encodeComponent(coeff, out, local));
        }
        return new TileResult(bytes.toByteArray(), indices, System.nanoTime() - started);
    }

    /** Copia el bloque LL (s0 x s0) del tile transformado al buffer de overview. */
    private void extractOverview(int[] coeff, byte[] overview, int ovW, int components,
                                 int s0, int tx, int ty, int comp) {
        for (int ly = 0; ly < s0; ly++) {
            int orow = (ty * s0 + ly) * ovW;
            for (int lx = 0; lx < s0; lx++) {
                int v = coeff[ly * tileSize + lx] + H2kFormat.LEVEL_SHIFT;
                v = v < 0 ? 0 : (v > 255 ? 255 : v);
                overview[(orow + tx * s0 + lx) * components + comp] = (byte) v;
            }
        }
    }

    private static byte[] encodeOverviewPng(byte[] overview, int ovW, int ovH,
                                            int components) throws IOException {
        BufferedImage img = new BufferedImage(ovW, ovH, components == 1
                ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ovH; y++) {
            for (int x = 0; x < ovW; x++) {
                int base = (y * ovW + x) * components;
                int rgb;
                if (components == 1) {
                    int v = overview[base] & 0xFF;
                    rgb = (v << 16) | (v << 8) | v;
                } else {
                    rgb = ((overview[base] & 0xFF) << 16)
                            | ((overview[base + 1] & 0xFF) << 8)
                            | (overview[base + 2] & 0xFF);
                }
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    // ---- Codificacion de un componente de un tile ------------------------

    private List<H2kFormat.PacketIndex> encodeComponent(
            int[] coeff, DataOutputStream out, Counting counting) throws IOException {

        Map<Long, int[]> buckets = Precincts.bucketize(coeff, tileSize, levels, precinct);
        List<H2kFormat.PacketIndex> index = new ArrayList<>();

        for (Map.Entry<Long, int[]> e : buckets.entrySet()) {
            long key = e.getKey();
            int[] values = e.getValue();
            int numPlanes = BitPlaneCoder.numPlanes(values);

            long[] offsets = new long[numPlanes];
            int[] lens = new int[numPlanes];
            if (numPlanes > 0) {
                byte[][] layers = BitPlaneCoder.encode(values, numPlanes, compressionLevel);
                for (int l = 0; l < numPlanes; l++) {
                    offsets[l] = counting.count;
                    out.write(layers[l]);
                    lens[l] = layers[l].length;
                }
            }
            index.add(new H2kFormat.PacketIndex(
                    Precincts.levelOf(key), Precincts.pyOf(key), Precincts.pxOf(key),
                    values.length, numPlanes, offsets, lens));
        }
        return index;
    }

    // ---- Extraccion de un componente desde la franja, con relleno de bordes

    private int[] extractFromStrip(byte[] strip, int width, int components,
                                   int x0, int validW, int validH, int comp) {
        int[] out = new int[tileSize * tileSize];
        for (int yy = 0; yy < tileSize; yy++) {
            int sy = Math.min(yy, validH - 1);   // replicar borde inferior
            int rowBase = sy * width * components;
            for (int xx = 0; xx < tileSize; xx++) {
                int sx = Math.min(xx, validW - 1); // replicar borde derecho
                int v = strip[rowBase + (x0 + sx) * components + comp] & 0xFF;
                out[yy * tileSize + xx] = v - H2kFormat.LEVEL_SHIFT;
            }
        }
        return out;
    }

    // ---- Serializacion de header e indices -------------------------------

    private void writeHeaderPlaceholder(DataOutputStream out, int components,
                                        int width, int height,
                                        int tilesX, int tilesY) throws IOException {
        byte[] header = new byte[H2kFormat.HEADER_SIZE];
        System.arraycopy(H2kFormat.MAGIC, 0, header, 0, 4);
        header[4] = H2kFormat.VERSION;
        header[5] = 0;                 // colorTransform = none
        header[6] = (byte) components;
        header[7] = 8;                 // bitDepth
        putInt(header, 8, tileSize);
        putInt(header, 12, levels);
        putInt(header, 16, precinct);
        putInt(header, 20, width);
        putInt(header, 24, height);
        putInt(header, 28, tilesX);
        putInt(header, 32, tilesY);
        // 36..43 tileDirOffset (se parchea al final)
        out.write(header);
    }

    private void writeTileIndex(DataOutputStream out, int validW, int validH,
                                List<List<H2kFormat.PacketIndex>> comps, long payloadOffset) throws IOException {
        out.writeInt(validW);
        out.writeInt(validH);
        for (List<H2kFormat.PacketIndex> precincts : comps) {
            out.writeInt(precincts.size());
            for (H2kFormat.PacketIndex p : precincts) {
                out.writeByte(p.level());
                out.writeShort(p.py());
                out.writeShort(p.px());
                out.writeInt(p.numCoeffs());
                out.writeByte(p.numPlanes());
                for (int l = 0; l < p.numPlanes(); l++) {
                    out.writeLong(payloadOffset + p.layerOffset()[l]);
                    out.writeInt(p.layerLen()[l]);
                }
            }
        }
    }

    private void patchHeader(Path path, long dirOffset, long ovOffset, int ovLen,
                             int ovW, int ovH) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(H2kFormat.TILEDIR_OFFSET_FIELD);
            raf.writeLong(dirOffset);
            raf.seek(H2kFormat.OVERVIEW_OFFSET_FIELD);
            raf.writeLong(ovOffset);
            raf.writeInt(ovLen);       // 52
            raf.writeInt(ovW);         // 56
            raf.writeInt(ovH);         // 60
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
