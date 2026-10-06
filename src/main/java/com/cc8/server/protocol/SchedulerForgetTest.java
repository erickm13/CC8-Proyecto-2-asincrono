package com.cc8.server.protocol;

import com.cc8.server.image.H2kReader;

import java.nio.file.Path;

/**
 * Verifica el ciclo LRU: tras "olvidar" un tile, el scheduler lo vuelve a
 * enumerar (reenvio) en el siguiente viewport, y solo ese tile. Requiere
 * images/sample.h2k. Uso: SchedulerForgetTest [sample.h2k]
 */
public final class SchedulerForgetTest {

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args.length > 0 ? args[0] : "images/sample.h2k");
        try (H2kReader reader = new H2kReader(path)) {
            var h = reader.header();
            Scheduler sch = new Scheduler(reader);

            // 1) Enviar todo una vez.
            sch.setViewport(0, 0, h.width(), h.height(), h.levels(), 999);
            int tile0PacketsFirst = drainCount(sch, 0);
            // (ya drenado todo; 'sent' contiene todos los paquetes)

            // 2) Reenumerar sin olvidar: no debe producir nada.
            sch.setViewport(0, 0, h.width(), h.height(), h.levels(), 999);
            int afterNoForget = drainCountAll(sch);
            if (afterNoForget != 0) {
                System.out.println("FALLO: reenumeró " + afterNoForget + " sin olvidar nada");
                System.exit(1);
            }
            System.out.println("OK  sin olvidar no se reenvía nada");

            // 3) Olvidar el tile 0 y reenumerar: deben salir solo paquetes del tile 0.
            sch.forget(0);
            sch.setViewport(0, 0, h.width(), h.height(), h.levels(), 999);
            int[] counts = drainCountsByTile(sch);
            int reTile0 = counts[0], others = 0;
            for (int t = 1; t < counts.length; t++) others += counts[t];
            if (others != 0) {
                System.out.println("FALLO: se reenviaron paquetes de otros tiles: " + others);
                System.exit(1);
            }
            if (reTile0 != tile0PacketsFirst) {
                System.out.printf("FALLO: reenvío tile0=%d != original=%d%n", reTile0, tile0PacketsFirst);
                System.exit(1);
            }
            System.out.printf("OK  tras forget(0) se reenvían exactamente los %d paquetes del tile 0%n", reTile0);
            System.out.println("SchedulerForgetTest paso.");
        }
    }

    private static int drainCount(Scheduler sch, int tileFilter) {
        int n = 0;
        while (sch.hasNext()) {
            byte[] p = sch.next();
            if (ImagePacket.decode(p).tile() == tileFilter) n++;
        }
        return n;
    }

    private static int drainCountAll(Scheduler sch) {
        int n = 0;
        while (sch.hasNext()) { sch.next(); n++; }
        return n;
    }

    private static int[] drainCountsByTile(Scheduler sch) {
        int[] counts = new int[4096];
        while (sch.hasNext()) {
            int tile = ImagePacket.decode(sch.next()).tile();
            if (tile < counts.length) counts[tile]++;
        }
        return counts;
    }
}
