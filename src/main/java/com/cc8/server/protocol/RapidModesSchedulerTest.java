package com.cc8.server.protocol;

import com.cc8.server.image.H2kReader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Runnable focused scheduler check: java -cp out com.cc8.server.protocol.RapidModesSchedulerTest. */
public final class RapidModesSchedulerTest {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length == 0 ? "images/sample.h2k" : args[0]);
        try (H2kReader reader = new H2kReader(file)) {
            for (int mode = 1; mode <= 2; mode++) {
                Scheduler scheduler = new Scheduler(reader, new SharedPacketStore());
                scheduler.setMode(mode);
                var h = reader.header();
                scheduler.setViewport(0, 0, h.width(), h.height(), h.levels(), 999,
                        1, 0, 0.8, 0);
                Map<String, Integer> lastLayer = new HashMap<>();
                boolean pastCoarse = false, pastBase = false;
                int count = 0;
                while (scheduler.hasNext()) {
                    ImagePacket.Packet packet = ImagePacket.decode(scheduler.next());
                    String key = packet.tile() + "/" + packet.comp() + "/" + packet.level()
                            + "/" + packet.py() + "/" + packet.px();
                    int previous = lastLayer.getOrDefault(key, -1);
                    check(packet.layer() == previous + 1, "noncontiguous layer in mode " + mode);
                    lastLayer.put(key, packet.layer());
                    if (mode == 2 && count == 50) {
                        scheduler.setViewport(0, 0, h.width(), h.height(), h.levels(), 999,
                                1, 0, 0.8, 0);
                    }
                    boolean coarse = packet.level() == 0 && packet.layer() == 0;
                    if (!coarse) pastCoarse = true;
                    check(!pastCoarse || !coarse, "coarse packet after detail in mode " + mode);
                    if (mode == 1) {
                        if (packet.layer() > 0) pastBase = true;
                        check(!pastBase || packet.layer() > 0,
                                "base packet after detail in coverage mode");
                    }
                    count++;
                }
                check(count > 0, "empty RAPID mode " + mode);
                scheduler.setViewport(0, 0, h.width(), h.height(), h.levels(), 999);
                check(!scheduler.hasNext(), "resent packets in mode " + mode);
                scheduler.forget(0);
                scheduler.setViewport(0, 0, h.width(), h.height(), h.levels(), 999);
                check(scheduler.hasNext(), "FORGET did not restore tile in mode " + mode);
                System.out.println("RAPID mode " + mode + ": " + count + " packets, order and FORGET OK");
            }
            Scheduler deadline = new Scheduler(reader);
            deadline.setMode(1);
            deadline.setViewport(0, 0, reader.header().tileSize(), reader.header().tileSize(),
                    reader.header().levels(), 1);
            Thread.sleep(270);
            deadline.next();
            check(deadline.deadlineMisses() > 0, "coarse deadline miss not counted");

            var h = reader.header();
            Scheduler loaded = new Scheduler(reader);
            loaded.setMode(1);
            loaded.setViewport(0, 0, h.width(), h.height(), h.levels(), 999,
                    0, 0, 0, 0.9);
            int baseOnly = drain(loaded, -1);
            loaded.setLoad(0);
            int resumed = drain(loaded, -1);
            check(baseOnly > 0 && resumed > 0, "optional detail did not resume after load drop");

            Scheduler noPrediction = new Scheduler(reader);
            noPrediction.setMode(2);
            noPrediction.setViewport(0, 0, h.tileSize(), h.tileSize(), 0, 1,
                    1, 0, 0, 0);
            check(drain(noPrediction, 1) == 0, "zero-confidence prefetch occurred");
            Scheduler prediction = new Scheduler(reader);
            prediction.setMode(2);
            prediction.setViewport(0, 0, h.tileSize(), h.tileSize(), 0, 1,
                    1, 0, 1, 0);
            check(drain(prediction, 1) > 0, "high-confidence directional prefetch missing");

            for (int mode = 0; mode <= 2; mode++) {
                Scheduler staged = new Scheduler(reader);
                staged.setMode(mode);
                staged.setViewport(0, 0, h.tileSize(), h.tileSize(), 0, 1,
                        0, 0, 0, 0);
                byte[] discarded = staged.next();
                byte[] transmitted = staged.next();
                staged.requeueUnsent(discarded);
                staged.setViewport(0, 0, h.tileSize(), h.tileSize(), 0, 1,
                        0, 0, 0, 0);
                check(staged.hasNext(), "discarded packet not restored in mode " + mode);
                check(samePacket(staged.next(), discarded), "wrong restored packet in mode " + mode);
                while (staged.hasNext()) {
                    check(!samePacket(staged.next(), transmitted),
                            "transmitted packet restored in mode " + mode);
                }
            }
        }
    }

    private static boolean samePacket(byte[] a, byte[] b) {
        ImagePacket.Packet x = ImagePacket.decode(a), y = ImagePacket.decode(b);
        return x.tile() == y.tile() && x.comp() == y.comp() && x.level() == y.level()
                && x.py() == y.py() && x.px() == y.px() && x.layer() == y.layer();
    }

    private static int drain(Scheduler scheduler, int tile) {
        int count = 0;
        while (scheduler.hasNext()) {
            ImagePacket.Packet packet = ImagePacket.decode(scheduler.next());
            if (tile < 0 || packet.tile() == tile) count++;
        }
        return count;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
