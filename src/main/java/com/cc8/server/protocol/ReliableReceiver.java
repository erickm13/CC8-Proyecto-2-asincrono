package com.cc8.server.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lado receptor (cliente) del transporte confiable. Por cada DATA:
 * <ul>
 *   <li>Entrega el payload al sumidero la primera vez que lo ve (dedup).</li>
 *   <li>Mantiene {@code rcvNxt} (siguiente SEQ contiguo esperado) y el conjunto
 *       de SEQ recibidos fuera de orden para construir los bloques SACK.</li>
 *   <li>Responde un ACK acumulativo + SACK, anunciando su ventana rwnd
 *       (flow control) y devolviendo el timestamp para medir RTT.</li>
 * </ul>
 * Referencias: RFC 9293 (ACK acumulativo), RFC 2018 (SACK).
 */
public final class ReliableReceiver {

    private static final int MAX_SACK_BLOCKS = 4;

    private final Link toSender;
    private final DeliverySink sink;

    private int rcvNxt = 0;
    private final TreeSet<Integer> outOfOrder = new TreeSet<>();
    private int rwnd;

    // Telemetria
    private long delivered = 0;
    private long duplicates = 0;

    public ReliableReceiver(Link toSender, DeliverySink sink, int initialRwnd) {
        this.toSender = toSender;
        this.sink = sink;
        this.rwnd = initialRwnd;
    }

    public void setRwnd(int rwnd) {
        this.rwnd = rwnd;
    }

    public long delivered() {
        return delivered;
    }

    public long duplicates() {
        return duplicates;
    }

    /** Procesa un datagrama DATA entrante. */
    public void onData(byte[] datagram) {
        Wire.Data d = Wire.decodeData(datagram);
        int seq = d.seq();

        if (seq == rcvNxt) {
            deliver(seq, d.payload());
            rcvNxt++;
            while (outOfOrder.remove(rcvNxt)) {
                rcvNxt++;
            }
        } else if (seq > rcvNxt) {
            if (outOfOrder.add(seq)) {
                deliver(seq, d.payload()); // entrega inmediata fuera de orden
            } else {
                duplicates++;
            }
        } else {
            duplicates++; // ya cubierto por rcvNxt
        }

        sendAck(d.sendTs());
    }

    private void deliver(int seq, byte[] payload) {
        delivered++;
        sink.deliver(seq, payload);
    }

    private void sendAck(long echoTs) {
        List<int[]> blocks = buildSackBlocks();
        int n = blocks.size();
        int[] starts = new int[n];
        int[] ends = new int[n];
        for (int i = 0; i < n; i++) {
            starts[i] = blocks.get(i)[0];
            ends[i] = blocks.get(i)[1];
        }
        toSender.send(Wire.encodeAck(rcvNxt, rwnd, echoTs, starts, ends));
    }

    /** Convierte el conjunto fuera de orden en rangos contiguos [start,end). */
    private List<int[]> buildSackBlocks() {
        List<int[]> blocks = new ArrayList<>();
        Integer start = null;
        int prev = -1;
        for (int seq : outOfOrder) {
            if (start == null) {
                start = seq;
                prev = seq;
            } else if (seq == prev + 1) {
                prev = seq;
            } else {
                blocks.add(new int[]{start, prev + 1});
                start = seq;
                prev = seq;
            }
        }
        if (start != null) {
            blocks.add(new int[]{start, prev + 1});
        }
        // El SACK mas util es el mas reciente/alto; limitar cantidad.
        if (blocks.size() > MAX_SACK_BLOCKS) {
            return blocks.subList(blocks.size() - MAX_SACK_BLOCKS, blocks.size());
        }
        return blocks;
    }
}
