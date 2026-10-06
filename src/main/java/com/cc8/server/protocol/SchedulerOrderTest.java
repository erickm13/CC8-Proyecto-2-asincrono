package com.cc8.server.protocol;

import com.cc8.server.image.H2kReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifica el orden del scheduler de utilidad/deadline (viewport = imagen
 * completa, todos deadline 0):
 * <ul>
 *   <li>El nivel de resolucion es NO DECRECIENTE (grueso -> fino).</li>
 *   <li>La utilidad/byte es NO CRECIENTE dentro de cada nivel (rate-distortion).</li>
 *   <li>Dentro de cada precinct las capas salen en orden 0,1,2,... (el cliente
 *       siempre tiene un prefijo contiguo y puede decodificar).</li>
 *   <li>El primer paquete es de nivel 0 (la sub-banda mas gruesa).</li>
 * </ul>
 * Uso: SchedulerOrderTest [sample.h2k]
 */
public final class SchedulerOrderTest {

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args.length > 0 ? args[0] : "images/sample.h2k");
        try (H2kReader reader = new H2kReader(path)) {
            var h = reader.header();
            Scheduler sch = new Scheduler(reader);
            sch.setViewport(0, 0, h.width(), h.height(), h.levels(), 999);

            List<ImagePacket.Packet> seq = new ArrayList<>();
            while (sch.hasNext()) {
                seq.add(ImagePacket.decode(sch.next()));
            }
            System.out.printf("paquetes en cola: %d%n", seq.size());

            // 1) Primer paquete = nivel 0.
            if (seq.get(0).level() != 0) {
                System.out.println("FALLO: el primer paquete no es nivel 0: " + seq.get(0).level());
                System.exit(1);
            }
            System.out.println("OK  el primer paquete es de la sub-banda mas gruesa (nivel 0)");

            // 2) (nivel, capa) lexicográficamente no decreciente, y utilidad/byte
            //    no creciente dentro de cada grupo (nivel, capa).
            int prevLevel = -1, prevLayer = -1;
            double prevUtil = Double.POSITIVE_INFINITY;
            for (ImagePacket.Packet p : seq) {
                if (p.level() < prevLevel
                        || (p.level() == prevLevel && p.layer() < prevLayer)) {
                    System.out.printf("FALLO: (nivel,capa) decreció: (%d,%d) tras (%d,%d)%n",
                            p.level(), p.layer(), prevLevel, prevLayer);
                    System.exit(1);
                }
                if (p.level() != prevLevel || p.layer() != prevLayer) {
                    prevLevel = p.level();
                    prevLayer = p.layer();
                    prevUtil = Double.POSITIVE_INFINITY;   // nuevo grupo (nivel, capa)
                }
                double u = utility(p);
                if (u > prevUtil + 1e-6) {
                    System.out.printf("FALLO: utilidad aumentó dentro de (nivel %d, capa %d): %.3g > %.3g%n",
                            p.level(), p.layer(), u, prevUtil);
                    System.exit(1);
                }
                prevUtil = u;
            }
            System.out.println("OK  orden (nivel, capa) + utilidad/byte no creciente por plano (RLCP + RD)");

            // 3) Capas por precinct en orden ascendente (decodable por prefijo).
            Map<String, Integer> lastLayer = new HashMap<>();
            for (ImagePacket.Packet p : seq) {
                String key = p.tile() + "/" + p.comp() + "/" + p.level() + "/" + p.py() + "/" + p.px();
                int last = lastLayer.getOrDefault(key, -1);
                if (p.layer() != last + 1) {
                    System.out.printf("FALLO: capa fuera de orden en %s: %d tras %d%n", key, p.layer(), last);
                    System.exit(1);
                }
                lastLayer.put(key, p.layer());
            }
            System.out.println("OK  cada precinct recibe sus capas en orden 0,1,2,... (contiguo)");
            System.out.println("SchedulerOrderTest paso.");
        }
    }

    private static double utility(ImagePacket.Packet p) {
        int plane = p.numPlanes() - 1 - p.layer();
        double gain = (double) p.numCoeffs() * Math.scalb(1.0, 2 * plane);
        return gain / Math.max(1, p.data().length);
    }
}
