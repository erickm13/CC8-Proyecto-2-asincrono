package com.cc8.server.protocol;

import com.cc8.server.image.ClientReconstructor;
import com.cc8.server.image.H2kFormat;
import com.cc8.server.image.H2kReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Black-box RAPID checks against an already running server. */
final class RapidIntegrationTest {
    private static final int RWND = 1 << 20;
    private static final int FLAG_BATCH = Wire.FLAG_BATCH;
    private static final int FLAG_RETX = Wire.FLAG_RETX;

    private static final class Acc {
        int level, py, px, numCoeffs, numPlanes;
        byte[][] layers;
    }

    private record Frame(byte[] raw, Wire.Data data) { }

    private static final class Peer implements WebSocket.Listener, AutoCloseable {
        private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<Integer, Frame> bySequence = new HashMap<>();
        private final AtomicLong bytesReceived = new AtomicLong();
        private final AtomicLong framesReceived = new AtomicLong();
        private volatile WebSocket socket;
        private volatile Throwable failure;
        private long batchFrames;

        Peer(String httpBase) throws Exception {
            URI uri = URI.create(httpBase.replaceFirst("^http", "ws") + "/stream");
            socket = http.newWebSocketBuilder().buildAsync(uri, this).get(8, TimeUnit.SECONDS);
        }

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            synchronized (fragments) {
                fragments.writeBytes(part);
                if (last) {
                    byte[] message = fragments.toByteArray();
                    fragments.reset();
                    incoming.offer(message);
                    bytesReceived.addAndGet(message.length);
                    framesReceived.incrementAndGet();
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            failure = error;
            incoming.offer(new byte[0]);
        }

        synchronized void send(byte[] bytes) throws Exception {
            check(failure == null, "WebSocket failed: " + failure);
            socket.sendBinary(ByteBuffer.wrap(bytes), true).get(8, TimeUnit.SECONDS);
        }

        void hello(int mode) throws Exception {
            send(new byte[]{Wire.T_HELLO, 1, (byte) mode});
        }

        void viewport(int x, int y, int w, int h, int level) throws Exception {
            byte[] b = new byte[18];
            b[0] = Wire.T_VIEWPORT;
            Wire.putInt(b, 1, x); Wire.putInt(b, 5, y);
            Wire.putInt(b, 9, w); Wire.putInt(b, 13, h);
            b[17] = (byte) level;
            send(b);
        }

        void forget(int... tiles) throws Exception {
            byte[] b = new byte[3 + tiles.length * 4];
            b[0] = Wire.T_FORGET;
            Wire.putShort(b, 1, tiles.length);
            for (int i = 0; i < tiles.length; i++) Wire.putInt(b, 3 + i * 4, tiles[i]);
            send(b);
        }

        Frame nextData(Duration timeout) throws Exception {
            long end = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < end) {
                check(failure == null, "WebSocket failed: " + failure);
                long left = Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
                byte[] raw = incoming.poll(left, TimeUnit.MILLISECONDS);
                if (raw == null) return null;
                check(raw.length > 0, "WebSocket closed after an error: " + failure);
                if ((raw[0] & 0xFF) == Wire.T_FIN) continue;
                check((raw[0] & 0xFF) == Wire.T_DATA,
                        "unexpected WebSocket message type " + (raw[0] & 0xFF));
                Wire.Data data = Wire.decodeData(raw);
                check(data.payload().length <= 0xFFFF, "DATA payload exceeds u16 framing");
                Frame frame = new Frame(raw, data);
                Frame previous = bySequence.putIfAbsent(data.seq(), frame);
                if (previous != null) {
                    check((data.flags() & FLAG_RETX) != 0, "duplicate DATA sequence lacks RETX flag");
                    check((previous.data().flags() & ~FLAG_RETX) == (data.flags() & ~FLAG_RETX),
                            "retransmission changed DATA flags");
                    check(java.util.Arrays.equals(previous.data().payload(), data.payload()),
                            "retransmission changed DATA payload");
                }
                checkBatchBounds(data);
                return frame;
            }
            return null;
        }

        void ack(Frame frame) throws Exception {
            Wire.Data data = frame.data();
            send(Wire.encodeAck(data.seq() + 1, RWND, data.sendTs(), new int[0], new int[0]));
        }

        List<ImagePacket.Packet> imagePackets(Frame frame, int expectedMode) {
            Wire.Data data = frame.data();
            checkModeFrame(data, expectedMode);
            boolean batch = (data.flags() & FLAG_BATCH) != 0;
            if (batch) batchFrames++;
            List<byte[]> encoded = batch ? Wire.decodeBatch(data.payload()) : List.of(data.payload());
            check(encoded.size() <= Wire.MAX_BATCH_PACKETS, "batch has more than 16 image packets");
            List<ImagePacket.Packet> packets = new ArrayList<>(encoded.size());
            for (byte[] packet : encoded) packets.add(ImagePacket.decode(packet));
            return packets;
        }

        private static void checkModeFrame(Wire.Data data, int expectedMode) {
            if (expectedMode == 0) {
                check((data.flags() & FLAG_BATCH) == 0, "legacy mode emitted a batch");
            }
        }

        private static void checkBatchBounds(Wire.Data data) {
            if ((data.flags() & FLAG_BATCH) != 0) {
                check(data.payload().length <= Wire.MAX_BATCH_PAYLOAD,
                        "batch payload exceeds 32768 bytes: " + data.payload().length);
                List<byte[]> packets = Wire.decodeBatch(data.payload());
                check(!packets.isEmpty() && packets.size() <= Wire.MAX_BATCH_PACKETS,
                        "batch count is outside 1..16");
            }
        }

        long batchFrames() { return batchFrames; }
        long bytesReceived() { return bytesReceived.get(); }
        long framesReceived() { return framesReceived.get(); }

        @Override
        public void close() throws Exception {
            WebSocket ws = socket;
            if (ws != null && !ws.isOutputClosed()) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(3, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length >= 3, "usage: RapidIntegrationTest http://host:port original.png image.h2k");
        String httpBase = args[0];
        Path originalPath = Path.of(args[1]);
        Path h2kPath = Path.of(args[2]);
        BufferedImage original = ImageIO.read(originalPath.toFile());
        check(original != null, "could not read source image " + originalPath);

        try (H2kReader reader = new H2kReader(h2kPath)) {
            H2kFormat.Header header = reader.header();
            check(original.getWidth() == header.width() && original.getHeight() == header.height(),
                    "source dimensions do not match the running .h2k image");
            batchBoundaryCheck();
            schedulerBehaviorChecks(reader, header);
            legacyLiveChecks(httpBase, original, header);
            for (int mode = 1; mode <= 2; mode++) {
                reconstructMode(httpBase, original, header, mode);
            }
            switchingCheck(httpBase, header);
            fairnessCheck(httpBase, header);
        }
        System.out.println("OK live RAPID: legacy retransmit/FORGET/order, modes 0-2 reconstruction, switching, batches, and two-session progress");
    }

    private static void batchBoundaryCheck() {
        List<byte[]> sourcePackets = new ArrayList<>();
        for (int i = 0; i < 9; i++) sourcePackets.add(new byte[4094]);
        SegmentSource source = new SegmentSource() {
            int next;
            public boolean hasNext() { return next < sourcePackets.size(); }
            public byte[] next() { return sourcePackets.get(next++); }
        };
        List<byte[]> sent = new ArrayList<>();
        ReliableSender sender = new ReliableSender(bytes -> sent.add(bytes.clone()), source,
                () -> 0, false);
        sender.setBatchingEnabled(true);
        check(sender.pumpOne(Wire.DATA_HEADER_SIZE + Wire.MAX_BATCH_PAYLOAD - 1) == 0,
                "sender exceeded a byte allowance one byte below the exact batch boundary");
        check(sender.sndNxt() == 0, "declined boundary batch consumed a sequence number");
        check(sender.pumpOne(Wire.DATA_HEADER_SIZE + Wire.MAX_BATCH_PAYLOAD)
                        == Wire.DATA_HEADER_SIZE + Wire.MAX_BATCH_PAYLOAD,
                "sender did not accept a DATA frame at the exact 32768-byte batch boundary");
        Wire.Data first = Wire.decodeData(sent.get(0));
        check((first.flags() & FLAG_BATCH) != 0 && first.payload().length == 32768,
                "exact-boundary DATA was not emitted as a 32768-byte batch");
        check(Wire.decodeBatch(first.payload()).size() == 8,
                "exact-boundary batch did not contain all eight packets");

        sender.onAck(Wire.encodeAck(1, RWND, 0, new int[0], new int[0]));
        sender.pumpOne(Wire.DATA_HEADER_SIZE + Wire.MAX_BATCH_PAYLOAD);
        Wire.Data overflow = Wire.decodeData(sent.get(1));
        check(overflow.seq() == 1 && overflow.payload().length == 4096,
                "packet beyond the 32768-byte boundary was not deferred intact");
        check(Wire.decodeBatch(overflow.payload()).size() == 1,
                "overflow packet was lost or merged into an oversized batch");
    }

    private static void schedulerBehaviorChecks(H2kReader reader, H2kFormat.Header h) {
        Scheduler coverage = new Scheduler(reader, new SharedPacketStore());
        coverage.setMode(1);
        coverage.setViewport(0, 0, h.width(), h.height(), h.levels(), 999,
                0, 0, 0, 0.9);
        List<ImagePacket.Packet> loaded = drainScheduler(coverage);
        check(!loaded.isEmpty(), "mode A under load produced no base coverage");
        check(loaded.stream().allMatch(p -> p.layer() == 0),
                "mode A high load sent detail before base coverage");
        coverage.setLoad(0);
        List<ImagePacket.Packet> resumed = drainScheduler(coverage);
        check(resumed.stream().anyMatch(p -> p.layer() > 0),
                "mode A did not resume detail after load dropped");

        Set<Integer> noPrediction = predictedTiles(reader, h, 0, 0, 0);
        Set<Integer> lowRight = predictedTiles(reader, h, 1, 0, 0.25);
        Set<Integer> highRight = predictedTiles(reader, h, 1, 0, 1);
        Set<Integer> highLeft = predictedTiles(reader, h, -1, 0, 1);
        check(noPrediction.equals(Set.of(1, 4)),
                "mode B zero confidence should deliver only the two visible tiles: " + noPrediction);
        check(lowRight.contains(2) && !lowRight.contains(5),
                "mode B low confidence did not narrow rightward prediction: " + lowRight);
        check(highRight.containsAll(Set.of(2, 5)) && !highRight.contains(0),
                "mode B high rightward confidence selected the wrong strip: " + highRight);
        check(highLeft.containsAll(Set.of(0, 3)) && !highLeft.contains(2),
                "mode B leftward prediction selected the wrong strip: " + highLeft);
    }

    private static Set<Integer> predictedTiles(H2kReader reader, H2kFormat.Header h,
                                                double dx, double dy, double confidence) {
        Scheduler scheduler = new Scheduler(reader, new SharedPacketStore());
        scheduler.setMode(2);
        scheduler.setViewport(h.tileSize(), 0, h.tileSize(), h.height(),
                0, 1, dx, dy, confidence, 0);
        Set<Integer> tiles = new HashSet<>();
        for (ImagePacket.Packet p : drainScheduler(scheduler)) tiles.add(p.tile());
        return tiles;
    }

    private static List<ImagePacket.Packet> drainScheduler(Scheduler scheduler) {
        List<ImagePacket.Packet> packets = new ArrayList<>();
        int guard = 0;
        while (scheduler.hasNext()) {
            byte[] bytes = scheduler.next();
            if (bytes == null) break;
            packets.add(ImagePacket.decode(bytes));
            check(++guard <= 100_000, "scheduler did not drain within test limit");
        }
        return packets;
    }

    private static void legacyLiveChecks(String httpBase, BufferedImage original,
                                         H2kFormat.Header header) throws Exception {
        try (Peer peer = new Peer(httpBase)) {
            peer.viewport(0, 0, header.width(), header.height(), header.levels());
            Frame first = peer.nextData(Duration.ofSeconds(8));
            check(first != null, "legacy no-HELLO session received no DATA");
            check(first.data().seq() == 0, "legacy first DATA sequence is not zero");
            check((first.data().flags() & (FLAG_BATCH | FLAG_RETX)) == 0,
                    "legacy first DATA must be raw and unretransmitted");
            List<ImagePacket.Packet> packets = new ArrayList<>(peer.imagePackets(first, 0));

            Frame retx = peer.nextData(Duration.ofSeconds(6));
            check(retx != null, "legacy sender did not retransmit after an omitted ACK");
            check(retx.data().seq() == first.data().seq(), "timeout changed the retransmitted sequence");
            check((retx.data().flags() & FLAG_RETX) != 0, "timeout retransmission lacks RETX flag");
            check(java.util.Arrays.equals(retx.data().payload(), first.data().payload()),
                    "timeout retransmission payload differs from first send");
            peer.ack(retx);

            List<Frame> firstPass = drain(peer, 0, Duration.ofSeconds(90), Duration.ofMillis(1500));
            Set<String> logical = new HashSet<>();
            for (ImagePacket.Packet packet : packets) logical.add(packetKey(packet));
            for (Frame frame : firstPass) {
                if (frame.data().seq() == first.data().seq()) continue;
                for (ImagePacket.Packet packet : peer.imagePackets(frame, 0)) {
                    check(logical.add(packetKey(packet)), "legacy sent a duplicate image packet without FORGET");
                    packets.add(packet);
                }
            }
            check(!packets.isEmpty() && packets.get(0).level() == 0,
                    "legacy default order did not start with coarse level 0");
            checkContiguousLayers(packets, "legacy default order");
            reconstruct(original, header, packets, "mode 0 legacy no-HELLO");

            Map<Integer, Integer> beforeByTile = countByTile(packets);
            int expectedTileZero = beforeByTile.getOrDefault(0, 0);
            check(expectedTileZero > 0, "legacy full viewport did not deliver tile 0");
            peer.forget(0);
            peer.viewport(0, 0, header.width(), header.height(), header.levels());
            List<Frame> forgotten = drain(peer, 0, Duration.ofSeconds(30), Duration.ofMillis(1200));
            int count = 0;
            for (Frame frame : forgotten) {
                for (ImagePacket.Packet packet : peer.imagePackets(frame, 0)) {
                    check(packet.tile() == 0, "FORGET(0) resent unrelated tile " + packet.tile());
                    count++;
                }
                peer.ack(frame);
            }
            check(count == expectedTileZero,
                    "FORGET(0) resent " + count + " packets; expected " + expectedTileZero);
        }
    }

    private static void reconstructMode(String httpBase, BufferedImage original,
                                        H2kFormat.Header header, int mode) throws Exception {
        try (Peer peer = new Peer(httpBase)) {
            peer.hello(mode);
            peer.viewport(0, 0, header.width(), header.height(), header.levels());
            List<Frame> frames = drain(peer, mode, Duration.ofSeconds(90), Duration.ofMillis(1500));
            List<ImagePacket.Packet> packets = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (Frame frame : frames) {
                for (ImagePacket.Packet packet : peer.imagePackets(frame, mode)) {
                    check(unique.add(packetKey(packet)), "mode " + mode + " repeated an image packet");
                    packets.add(packet);
                }
            }
            check(!packets.isEmpty(), "mode " + mode + " produced no image packets");
            check(peer.batchFrames() > 0, "mode " + mode + " produced no batched DATA frames");
            checkContiguousLayers(packets, "mode " + mode);
            reconstruct(original, header, packets, "mode " + mode);
            System.out.printf("Mode %d live: %d DATA frames, %d image packets, %d batched frames%n",
                    mode, frames.size(), packets.size(), peer.batchFrames());
        }
    }

    private static void switchingCheck(String httpBase, H2kFormat.Header h) throws Exception {
        try (Peer peer = new Peer(httpBase)) {
            Set<String> seen = new HashSet<>();
            peer.viewport(0, 0, h.width(), h.height(), 0);
            List<Frame> legacy = drain(peer, 0, Duration.ofSeconds(20), Duration.ofMillis(1000));
            check(!legacy.isEmpty(), "mode-switch legacy phase received no data");
            int initialCount = 0;
            for (Frame frame : legacy) {
                for (ImagePacket.Packet packet : peer.imagePackets(frame, 0)) {
                    check(packet.level() == 0, "initial switch phase exceeded level 0");
                    check(seen.add(packetKey(packet)), "initial switch phase repeated a packet");
                    initialCount++;
                }
            }
            check(initialCount > 0, "initial switch phase had no image packets");

            int[] modes = {2, 1, 0};
            for (int i = 0; i < modes.length; i++) {
                int mode = modes[i];
                int maxLevel = i + 1;
                peer.hello(mode);
                peer.viewport(0, 0, h.width(), h.height(), maxLevel);
                Frame frame = peer.nextData(Duration.ofSeconds(8));
                check(frame != null, "no DATA after switching to mode " + mode);
                List<ImagePacket.Packet> firstPackets = peer.imagePackets(frame, mode);
                for (ImagePacket.Packet packet : firstPackets) {
                    check(packet.level() == maxLevel,
                            "mode " + mode + " switch resent old level " + packet.level());
                    check(seen.add(packetKey(packet)), "mode " + mode + " switch repeated an image packet");
                }
                if (mode == 0) check((frame.data().flags() & FLAG_BATCH) == 0,
                        "switch back to mode 0 kept batch framing");
                else check((frame.data().flags() & FLAG_BATCH) != 0,
                        "switch to mode " + mode + " did not enable batch framing");
                peer.ack(frame);
                List<Frame> rest = drain(peer, mode, Duration.ofSeconds(20), Duration.ofMillis(1000));
                for (Frame pending : rest) {
                    for (ImagePacket.Packet packet : peer.imagePackets(pending, mode)) {
                        check(packet.level() == maxLevel,
                                "mode " + mode + " switch resent old level " + packet.level());
                        check(seen.add(packetKey(packet)), "mode " + mode + " switch repeated an image packet");
                    }
                }
            }
        }
    }

    private static void fairnessCheck(String httpBase, H2kFormat.Header h) throws Exception {
        try (Peer a = new Peer(httpBase); Peer b = new Peer(httpBase)) {
            a.hello(2); b.hello(2);
            a.viewport(0, 0, h.width(), h.height(), h.levels());
            b.viewport(0, 0, h.width(), h.height(), h.levels());
            long start = System.nanoTime();
            long end = start + TimeUnit.SECONDS.toNanos(2);
            Thread ta = ackUntil(a, end), tb = ackUntil(b, end);
            ta.join(2500); tb.join(2500);
            long bytesA = a.bytesReceived(), bytesB = b.bytesReceived();
            check(bytesA > 0 && bytesB > 0, "one live session received no bytes");
            long smaller = Math.min(bytesA, bytesB), larger = Math.max(bytesA, bytesB);
            check(larger <= smaller * 3,
                    "two identical backlogged sessions were not byte-fair: " + bytesA + " vs " + bytesB);
            System.out.printf("Two-session progress: %d vs %d bytes in %.1f s%n",
                    bytesA, bytesB, (System.nanoTime() - start) / 1_000_000_000.0);
        }
    }

    private static Thread ackUntil(Peer peer, long deadline) {
        Thread thread = new Thread(() -> {
            while (System.nanoTime() < deadline) {
                try {
                    Frame frame = peer.nextData(Duration.ofMillis(150));
                    if (frame != null) peer.ack(frame);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, "rapid-ack-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static List<Frame> drain(Peer peer, int mode, Duration maxDuration,
                                     Duration quietPeriod) throws Exception {
        long deadline = System.nanoTime() + maxDuration.toNanos();
        List<Frame> frames = new ArrayList<>();
        long lastData = System.nanoTime();
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            long quietLeft = quietPeriod.toNanos() - (now - lastData);
            if (quietLeft <= 0) return frames;
            Frame frame = peer.nextData(Duration.ofNanos(Math.min(quietLeft, deadline - now)));
            if (frame == null) {
                if (System.nanoTime() - lastData >= quietPeriod.toNanos()) return frames;
                continue;
            }
            Peer.checkModeFrame(frame.data(), mode);
            frames.add(frame);
            lastData = System.nanoTime();
            peer.ack(frame);
        }
        throw new AssertionError("RAPID mode " + mode + " failed to quiesce within " + maxDuration);
    }

    private static List<ClientReconstructor.Precinct> precincts(
            Map<Integer, Map<Integer, Map<Long, Acc>>> store, int tile, int comp) {
        List<ClientReconstructor.Precinct> list = new ArrayList<>();
        Map<Integer, Map<Long, Acc>> byComp = store.get(tile);
        if (byComp == null) return list;
        Map<Long, Acc> byKey = byComp.get(comp);
        if (byKey == null) return list;
        for (Acc a : byKey.values()) {
            int received = 0;
            while (received < a.numPlanes && a.layers[received] != null) received++;
            list.add(new ClientReconstructor.Precinct(
                    a.level, a.py, a.px, a.numCoeffs, a.numPlanes, a.layers, received));
        }
        return list;
    }

    private static void reconstruct(BufferedImage original, H2kFormat.Header h,
                                    List<ImagePacket.Packet> packets, String context) {
        Map<Integer, Map<Integer, Map<Long, Acc>>> store = new HashMap<>();
        for (ImagePacket.Packet p : packets) {
            Acc a = store.computeIfAbsent(p.tile(), k -> new HashMap<>())
                    .computeIfAbsent(p.comp(), k -> new HashMap<>())
                    .computeIfAbsent(key(p.level(), p.py(), p.px()), k -> new Acc());
            if (a.layers == null) {
                a.level = p.level(); a.py = p.py(); a.px = p.px();
                a.numCoeffs = p.numCoeffs(); a.numPlanes = p.numPlanes();
                a.layers = new byte[p.numPlanes()][];
            }
            check(p.layer() < a.layers.length, "packet layer exceeds precinct plane count");
            a.layers[p.layer()] = p.data();
        }
        for (int ty = 0; ty < h.tilesY(); ty++) {
            for (int tx = 0; tx < h.tilesX(); tx++) {
                int tile = ty * h.tilesX() + tx;
                int[][] comp = new int[h.components()][];
                for (int c = 0; c < h.components(); c++) {
                    comp[c] = ClientReconstructor.component(h, precincts(store, tile, c));
                }
                int x0 = tx * h.tileSize(), y0 = ty * h.tileSize();
                int validW = Math.min(h.tileSize(), h.width() - x0);
                int validH = Math.min(h.tileSize(), h.height() - y0);
                for (int y = 0; y < validH; y++) {
                    for (int x = 0; x < validW; x++) {
                        int p = y * h.tileSize() + x;
                        int rgb = (comp[0][p] << 16) | (comp[1][p] << 8) | comp[2][p];
                        int sourceRgb = original.getRGB(x0 + x, y0 + y) & 0xFFFFFF;
                        check(sourceRgb == rgb, context + " reconstruction mismatch at " + (x0 + x) + "," + (y0 + y));
                    }
                }
            }
        }
    }

    private static Map<Integer, Integer> countByTile(List<ImagePacket.Packet> packets) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ImagePacket.Packet p : packets) counts.merge(p.tile(), 1, Integer::sum);
        return counts;
    }

    private static void checkContiguousLayers(List<ImagePacket.Packet> packets, String context) {
        Map<String, Integer> lastLayer = new HashMap<>();
        for (ImagePacket.Packet p : packets) {
            String key = packetPrefix(p);
            int expected = lastLayer.getOrDefault(key, -1) + 1;
            check(p.layer() == expected, context + " has layer " + p.layer() + " after " + (expected - 1) + " for " + key);
            lastLayer.put(key, p.layer());
        }
    }

    private static String packetKey(ImagePacket.Packet p) {
        return packetPrefix(p) + "/" + p.layer();
    }

    private static String packetPrefix(ImagePacket.Packet p) {
        return p.tile() + "/" + p.comp() + "/" + p.level() + "/" + p.py() + "/" + p.px();
    }

    private static long key(int level, int py, int px) {
        return ((long) level << 40) | ((long) py << 20) | px;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
