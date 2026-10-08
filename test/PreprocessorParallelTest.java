package com.cc8.server.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Focused acceptance test for preprocessing CLI options and tile parallelism. */
public final class PreprocessorParallelTest {
    private static final int WIDTH = 130;
    private static final int HEIGHT = 70;
    private static final int TILE = 64;
    private static final int LEVELS = 4;
    private static final int PRECINCT = 16;

    private PreprocessorParallelTest() { }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("preprocessor-parallel-test-");
        try {
            BufferedImage expected = syntheticImage();
            Path input = temp.resolve("edge-rgb.png");
            check(ImageIO.write(expected, "png", input.toFile()), "PNG writer unavailable");

            Path defaults = temp.resolve("default.h2k");
            Result defaultRun = runCli(input, defaults);
            check(defaultRun.exitCode() == 0, "default CLI failed:\n" + defaultRun.output());
            check(option(defaultRun.output(), "compression") == 6,
                    "default compression must be 6:\n" + defaultRun.output());
            int expectedDefaultThreads = Math.max(1,
                    Math.min(4, Runtime.getRuntime().availableProcessors()));
            check(option(defaultRun.output(), "threads") == expectedDefaultThreads,
                    "CLI default threads must be min(4, availableProcessors):\n"
                            + defaultRun.output());

            Path serial = temp.resolve("serial.h2k");
            Result serialRun = runCli(input, serial,
                    "--compression-level=6", "--threads=1");
            check(serialRun.exitCode() == 0, "serial CLI failed:\n" + serialRun.output());
            check(option(serialRun.output(), "compression") == 6
                            && option(serialRun.output(), "threads") == 1,
                    "explicit serial options were not applied:\n" + serialRun.output());

            Path parallel = temp.resolve("parallel.h2k");
            Result parallelRun = runCli(input, parallel,
                    "--compression-level=6", "--threads=4");
            check(parallelRun.exitCode() == 0, "parallel CLI failed:\n" + parallelRun.output());
            check(option(parallelRun.output(), "compression") == 6
                            && option(parallelRun.output(), "threads") == 4,
                    "explicit parallel options were not applied:\n" + parallelRun.output());

            byte[] serialBytes = Files.readAllBytes(serial);
            check(Arrays.equals(serialBytes, Files.readAllBytes(parallel)),
                    "serial and 4-thread H2K files differ");
            check(Arrays.equals(serialBytes, Files.readAllBytes(defaults)),
                    "default-thread H2K file differs from serial H2K file");
            verifyExactPixels(serial, expected);
            verifyExactPixels(parallel, expected);
            verifyExactPixels(defaults, expected);

            Path compressionOne = temp.resolve("compression-1.h2k");
            Result compressionRun = runCli(input, compressionOne,
                    "--compression-level=1", "--threads=1");
            check(compressionRun.exitCode() == 0,
                    "explicit compression level failed:\n" + compressionRun.output());
            check(option(compressionRun.output(), "compression") == 1,
                    "explicit compression level was not applied:\n" + compressionRun.output());
            check(!Arrays.equals(serialBytes, Files.readAllBytes(compressionOne)),
                    "compression level 1 did not change the encoded H2K bytes");
            verifyExactPixels(compressionOne, expected);

            Result badThreads = runCli(input, temp.resolve("bad-threads.h2k"), "--threads=0");
            check(badThreads.exitCode() != 0, "threads=0 should be rejected");
            Result badCompression = runCli(input, temp.resolve("bad-compression.h2k"),
                    "--compression-level=10");
            check(badCompression.exitCode() != 0, "compression level 10 should be rejected");

            verifyMetrics(expected, temp);
            verifyFailureCleanup(temp);
            verifyWorkerFailurePropagation(expected, temp);

            System.out.printf("PreprocessorParallelTest passed: %dx%d RGB, tiles=3x2, "
                            + "serial/parallel bytes identical, exact decode, default threads=%d%n",
                    WIDTH, HEIGHT, H2kWriter.DEFAULT_THREADS);
        } finally {
            deleteTree(temp);
        }
    }

    private static void verifyMetrics(BufferedImage image, Path temp) throws Exception {
        Path serialFile = temp.resolve("metrics-serial.h2k");
        H2kWriter.Metrics serial = new H2kWriter(TILE, LEVELS, PRECINCT, 6, 1)
                .writeWithMetrics(new BufferedImageRowSource(image), serialFile);
        check(serial.readNanos() > 0 && serial.transformEncodeNanos() > 0
                        && serial.outputWriteNanos() > 0 && serial.elapsedNanos() > 0,
                "serial phase metrics must all be positive: " + serial);
        check(serial.readNanos() + serial.transformEncodeNanos() + serial.outputWriteNanos()
                        <= serial.elapsedNanos(),
                "serial phase metrics overlap or exceed wall time: " + serial);

        Path parallelFile = temp.resolve("metrics-parallel.h2k");
        H2kWriter.Metrics parallel = new H2kWriter(TILE, LEVELS, PRECINCT, 6, 4)
                .writeWithMetrics(new BufferedImageRowSource(image), parallelFile);
        check(parallel.readNanos() > 0 && parallel.transformEncodeNanos() > 0
                        && parallel.outputWriteNanos() > 0 && parallel.elapsedNanos() > 0,
                "parallel phase metrics must all be positive: " + parallel);
        check(parallel.readNanos() + parallel.outputWriteNanos() <= parallel.elapsedNanos(),
                "serialized read/output phases exceed wall time: " + parallel);
    }

    private static void verifyFailureCleanup(Path temp) throws Exception {
        FailingRows source = new FailingRows();
        Path output = temp.resolve("failed.h2k");
        try {
            new H2kWriter(TILE, LEVELS, PRECINCT, 6, 4).writeWithMetrics(source, output);
            throw new AssertionError("source failure should propagate");
        } catch (IOException expected) {
            check(expected.getMessage().contains("sentinel source failure"),
                    "unexpected source failure: " + expected);
        }
        check(source.closed, "RowSource must close after a failed read");
    }

    private static void verifyWorkerFailurePropagation(BufferedImage image, Path temp)
            throws Exception {
        Path output = temp.resolve("worker-failed.h2k");
        try {
            new H2kWriter(TILE, LEVELS, 0, 6, 4)
                    .writeWithMetrics(new BufferedImageRowSource(image), output);
            throw new AssertionError("tile worker failure should propagate");
        } catch (IOException expected) {
            check(expected.getMessage().contains("Error al codificar tile")
                            && expected.getCause() instanceof ArithmeticException,
                    "unexpected tile worker failure: " + expected);
        }
    }

    private static void verifyExactPixels(Path h2k, BufferedImage expected) throws Exception {
        try (H2kReader reader = new H2kReader(h2k)) {
            H2kFormat.Header header = reader.header();
            check(header.width() == WIDTH && header.height() == HEIGHT
                            && header.components() == 3 && header.tilesX() == 3 && header.tilesY() == 2,
                    "unexpected H2K header: " + header);
            BufferedImage actual = Decoder.reconstructFull(reader, Integer.MAX_VALUE);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    if ((expected.getRGB(x, y) & 0x00ff_ffff)
                            != (actual.getRGB(x, y) & 0x00ff_ffff)) {
                        throw new AssertionError("decoded pixel mismatch at " + x + "," + y);
                    }
                }
            }
        }
    }

    private static BufferedImage syntheticImage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int red = (x * 37 + y * 11) & 0xff;
                int green = (x * 7 + y * 53 + (x ^ y)) & 0xff;
                int blue = ((x / 3 + y / 5) % 2 == 0) ? (x * 3 & 0xff) : (y * 9 & 0xff);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private static Result runCli(Path input, Path output, String... options) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(javaBin);
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Preprocessor.class.getName());
        command.add(input.toString());
        command.add(output.toString());
        command.add(Integer.toString(TILE));
        command.add(Integer.toString(LEVELS));
        command.add(Integer.toString(PRECINCT));
        command.addAll(Arrays.asList(options));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes());
        return new Result(process.waitFor(), result);
    }

    private static int option(String output, String key) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(key) + "=(\\d+)").matcher(output);
        check(matcher.find(), "missing " + key + " in CLI output:\n" + output);
        return Integer.parseInt(matcher.group(1));
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Result(int exitCode, String output) { }

    private static final class FailingRows implements RowSource {
        private int calls;
        private boolean closed;

        @Override public int width() { return WIDTH; }
        @Override public int height() { return HEIGHT * 2; }
        @Override public int components() { return 3; }

        @Override
        public int readStrip(byte[] strip, int rows) throws IOException {
            if (calls++ > 0) throw new IOException("sentinel source failure");
            Arrays.fill(strip, (byte) 127);
            return rows;
        }

        @Override public void close() { closed = true; }
    }
}
