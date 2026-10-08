package com.cc8.server.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Benchmarks DEFLATE levels on the first 4096x2048 pixels of a PNG. */
public final class CompressionLevelBenchmark {
    private static final int CROP_WIDTH = 4096;
    private static final int CROP_HEIGHT = 2048;
    private static final int TILE = 512;
    private static final int LEVELS = 5;
    private static final int PRECINCT = 64;

    public static void main(String[] args) throws Exception {
        Path png = Path.of(args.length > 0 ? args[0]
                : "Imagen-55GB-comprimida/055-843-000-80450114.png");
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int[] levels = args.length > 2 ? new int[]{Integer.parseInt(args[2])} : new int[]{1, 6, 9};
        for (int level : levels) {
            run(png, level, threads);
        }
    }

    private static void run(Path png, int level, int threads) throws Exception {
        Path output = Files.createTempFile("compression-level-" + level, ".h2k");
        try {
            CroppedRows source = new CroppedRows(new PngStreamReader(png));
            H2kWriter writer = new H2kWriter(TILE, LEVELS, PRECINCT, level, threads);
            H2kWriter.Metrics metrics = writer.writeWithMetrics(source, output);
            long bytes = Files.size(output);
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(output)));

            try (H2kReader reader = new H2kReader(output)) {
                H2kFormat.Header header = reader.header();
                if (header.version() != H2kFormat.VERSION
                        || header.width() != source.width()
                        || header.height() != source.height()) {
                    throw new AssertionError("unexpected H2K header: " + header);
                }
                verifyPixels(source, Decoder.reconstructFull(reader, Integer.MAX_VALUE));
            }

            System.out.printf("level=%d threads=%d size=%d sha256=%s exact=true version=%d "
                            + "total=%.3fs read=%.3fs transform_encode=%.3fs output=%.3fs%n",
                    level, threads, bytes, sha256, H2kFormat.VERSION,
                    seconds(metrics.elapsedNanos()), seconds(metrics.readNanos()),
                    seconds(metrics.transformEncodeNanos()), seconds(metrics.outputWriteNanos()));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static void verifyPixels(CroppedRows source, BufferedImage decoded) {
        int width = source.width(), height = source.height(), components = source.components();
        byte[] expected = source.expected();
        if (decoded.getWidth() != width || decoded.getHeight() != height
                || source.rowsRead() != height) {
            throw new AssertionError("crop was not fully read or reconstructed");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * components;
                int r = expected[i] & 0xff;
                int g = components == 1 ? r : expected[i + 1] & 0xff;
                int b = components == 1 ? r : expected[i + 2] & 0xff;
                int want = (r << 16) | (g << 8) | b;
                if ((decoded.getRGB(x, y) & 0xffffff) != want) {
                    throw new AssertionError("pixel mismatch at " + x + "," + y);
                }
            }
        }
    }

    private static final class CroppedRows implements RowSource {
        private final PngStreamReader source;
        private final int width;
        private final int height;
        private final int components;
        private final byte[] expected;
        private final byte[] sourceRow;
        private int rowsRead;

        CroppedRows(PngStreamReader source) {
            this.source = source;
            this.width = Math.min(CROP_WIDTH, source.width());
            this.height = Math.min(CROP_HEIGHT, source.height());
            this.components = source.components();
            this.expected = new byte[Math.multiplyExact(
                    Math.multiplyExact(width, height), components)];
            this.sourceRow = new byte[Math.multiplyExact(source.width(), components)];
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public int components() { return components; }
        int rowsRead() { return rowsRead; }
        byte[] expected() { return expected; }

        @Override
        public int readStrip(byte[] strip, int rows) throws IOException {
            int count = Math.min(rows, height - rowsRead);
            if (count <= 0) return 0;
            int rowBytes = width * components;
            int got = 0;
            while (got < count && source.readStrip(sourceRow, 1) == 1) {
                System.arraycopy(sourceRow, 0, strip, got * rowBytes, rowBytes);
                System.arraycopy(sourceRow, 0, expected, rowsRead * rowBytes, rowBytes);
                rowsRead++;
                got++;
            }
            return got;
        }

        @Override public void close() throws IOException { source.close(); }
    }
}
