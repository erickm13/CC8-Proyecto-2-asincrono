package com.cc8.server.protocol;

import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Lado emisor (servidor) del transporte confiable. Implementa, sobre WebSocket,
 * mecanismos adaptados de TCP (RFC 9293) para gestionar la transmision de los
 * paquetes de imagen:
 *
 * <ul>
 *   <li><b>Ventana deslizante</b>: como maximo {@code min(cwnd, rwnd)} segmentos
 *       en vuelo (congestion + flow control).</li>
 *   <li><b>Slow start / congestion avoidance / fast recovery</b> (estilo Reno,
 *       RFC 5681): cwnd crece exponencial hasta ssthresh y luego lineal; ante
 *       perdida se reduce.</li>
 *   <li><b>Selective Repeat con SACK</b> (RFC 2018/6675): retransmite solo los
 *       segmentos faltantes, no los ya confirmados selectivamente.</li>
 *   <li><b>RTO adaptativo</b> (RFC 6298) con backoff exponencial y algoritmo de
 *       Karn (no muestrea RTT de retransmisiones).</li>
 * </ul>
 *
 * <p>La unidad de secuencia es el segmento (un paquete de imagen). El emisor es
 * accionado por eventos: {@link #onAck}, {@link #tick} (temporizador) y
 * {@link #pump} (intentar enviar mientras la ventana lo permita).
 */
public final class ReliableSender {

    private static final long MIN_RTO = 200;
    private static final long MAX_RTO = 60_000;
    private static final int DUP_ACK_THRESHOLD = 3;
    private static final int MAX_SACK_RETRANSMITS_PER_ACK = 4;

    private final Link toReceiver;
    private final SegmentSource source;
    private final LongSupplier clock;
    private final boolean autoPump;
    private boolean batchingEnabled;
    private byte[] stagedPayload;
    private boolean stagedBatch;
    private byte[] overflowPayload;

    /** Segmento sin confirmar (guardamos el payload para poder retransmitir). */
    private static final class Unacked {
        final byte[] payload;
        final boolean batch;
        boolean retransmitted;
        long lastSent;
        Unacked(byte[] payload, boolean batch, long lastSent) {
            this.payload = payload;
            this.batch = batch;
            this.lastSent = lastSent;
        }
    }

    // Ventana / secuencia
    private int sndUna = 0;   // menor SEQ sin confirmar
    private int sndNxt = 0;   // siguiente SEQ a asignar
    private int rwnd = 1 << 20;
    private final TreeMap<Integer, Unacked> buffer = new TreeMap<>();
    private final TreeSet<Integer> sacked = new TreeSet<>();

    // Congestion control
    private double cwnd = 1.0;
    private double ssthresh = 64.0;
    private int dupAckCount = 0;
    private boolean inRecovery = false;
    private int recoverPoint = 0;

    // RTT / RTO
    private double srtt = -1;
    private double rttvar = 0;
    private long rto = 1000;
    private long rtoDeadline = 0;
    private boolean timerRunning = false;

    // Telemetria
    private long sent = 0, retransmits = 0, timeouts = 0, fastRetx = 0;

    public ReliableSender(Link toReceiver, SegmentSource source, LongSupplier clock) {
        this(toReceiver, source, clock, true);
    }

    /** Allows a shared dispatcher to control new data while retaining the default standalone behavior. */
    public ReliableSender(Link toReceiver, SegmentSource source, LongSupplier clock,
                          boolean autoPump) {
        this.toReceiver = toReceiver;
        this.source = source;
        this.clock = clock;
        this.autoPump = autoPump;
    }

    // ---- Ventana ---------------------------------------------------------

    /** Ventana efectiva en segmentos: min(cwnd, rwnd). */
    public int window() {
        return Math.max(1, Math.min((int) cwnd, rwnd));
    }

    /** Segmentos realmente en vuelo (sin confirmar y sin SACK). */
    public int inFlight() {
        return (sndNxt - sndUna) - sacked.size();
    }

    /** ¿Termino? Sin nada en buffer y la fuente no tiene mas. */
    public boolean isDone() {
        return buffer.isEmpty() && stagedPayload == null && overflowPayload == null
                && !source.hasNext();
    }

    public void setBatchingEnabled(boolean enabled) {
        batchingEnabled = enabled;
    }

    /** Requeues prepared but untransmitted image packets before the source rebuilds its queue. */
    public void discardStaged() {
        if (stagedPayload != null) {
            if (stagedBatch) {
                for (byte[] packet : Wire.decodeBatch(stagedPayload)) {
                    source.requeueUnsent(packet);
                }
            } else {
                source.requeueUnsent(stagedPayload);
            }
        }
        if (overflowPayload != null) {
            source.requeueUnsent(overflowPayload);
        }
        stagedPayload = null;
        stagedBatch = false;
        overflowPayload = null;
    }

    /** True when new data can enter the current congestion and receive window. */
    public boolean canPump() {
        return inFlight() < window()
                && (stagedPayload != null || overflowPayload != null || source.hasNext());
    }

    /** Encoded DATA datagram size for the next payload, staging it if necessary. */
    public int nextWireBytes() {
        if (!canPump()) {
            return 0;
        }
        preparePayload();
        return stagedPayload == null ? 0 : Wire.DATA_HEADER_SIZE + stagedPayload.length;
    }

    /** Instante del proximo vencimiento de RTO, o Long.MAX_VALUE si no hay. */
    public long nextTimeoutAt() {
        return timerRunning && !buffer.isEmpty() ? rtoDeadline : Long.MAX_VALUE;
    }

    // ---- Envio -----------------------------------------------------------

    /** Envia nuevos segmentos mientras la ventana y la fuente lo permitan. */
    public void pump() {
        while (pumpOne(Integer.MAX_VALUE) > 0) { }
    }

    /** Sends at most one DATA datagram, and only when it fits the byte allowance. */
    public int pumpOne(int byteAllowance) {
        if (!canPump()) {
            return 0;
        }
        preparePayload();
        if (stagedPayload == null) {
            return 0;
        }
        int bytes = Wire.DATA_HEADER_SIZE + stagedPayload.length;
        if (bytes > byteAllowance) {
            return 0;
        }
        long now = clock.getAsLong();
        int seq = sndNxt;
        buffer.put(seq, new Unacked(stagedPayload, stagedBatch, now));
        if (!transmit(seq, now, false)) {
            buffer.remove(seq);
            return 0;
        }
        sndNxt++;
        stagedPayload = null;
        stagedBatch = false;
        startTimerIfIdle(now);
        return bytes;
    }

    private void preparePayload() {
        if (stagedPayload != null) {
            return;
        }
        byte[] first = takeNextPayload();
        if (first == null) {
            return;
        }
        if (!batchingEnabled || first.length + 2 > Wire.MAX_BATCH_PAYLOAD) {
            stagedPayload = first;
            return;
        }

        List<byte[]> packets = new ArrayList<>(Wire.MAX_BATCH_PACKETS);
        packets.add(first);
        int size = first.length + 2;
        while (packets.size() < Wire.MAX_BATCH_PACKETS && source.hasNext()) {
            byte[] packet = source.next();
            if (packet == null) {
                break;
            }
            if (size + packet.length + 2 > Wire.MAX_BATCH_PAYLOAD) {
                overflowPayload = packet;
                break;
            }
            packets.add(packet);
            size += packet.length + 2;
        }
        stagedPayload = Wire.encodeBatch(packets);
        stagedBatch = true;
    }

    private byte[] takeNextPayload() {
        if (overflowPayload != null) {
            byte[] payload = overflowPayload;
            overflowPayload = null;
            return payload;
        }
        return source.hasNext() ? source.next() : null;
    }

    private boolean transmit(int seq, long now, boolean isRetx) {
        Unacked u = buffer.get(seq);
        if (u == null) {
            return false;
        }
        int flags = (isRetx ? Wire.FLAG_RETX : 0) | (u.batch ? Wire.FLAG_BATCH : 0);
        if (!toReceiver.trySend(Wire.encodeData(seq, now, flags, u.payload))) {
            return false;
        }
        u.lastSent = now;
        sent++;
        if (isRetx) {
            u.retransmitted = true;
            retransmits++;
        }
        return true;
    }

    // ---- Recepcion de ACK ------------------------------------------------

    public void onAck(byte[] datagram) {
        long now = clock.getAsLong();
        Wire.Ack a = Wire.decodeAck(datagram);
        if (a.ack() < sndUna || a.ack() > sndNxt || a.rwnd() < 0) {
            return;
        }
        for (int i = 0; i < a.sackStart().length; i++) {
            if (a.sackStart()[i] < a.ack() || a.sackEnd()[i] <= a.sackStart()[i]
                    || a.sackEnd()[i] > sndNxt) {
                return;
            }
        }
        rwnd = a.rwnd();

        // Marcar segmentos confirmados selectivamente; muestrear RTT de los
        // que se enviaron una sola vez (validos para Karn aunque haya perdida).
        for (int i = 0; i < a.sackStart().length; i++) {
            for (int s = a.sackStart()[i]; s < a.sackEnd()[i]; s++) {
                if (s < sndUna) {
                    continue;
                }
                Unacked u = buffer.get(s);
                if (u != null && sacked.add(s) && !u.retransmitted) {
                    updateRtt(now - u.lastSent);
                }
            }
        }

        if (a.ack() > sndUna) {
            onNewAck(a, now);
        } else if (a.ack() == sndUna) {
            onDuplicateAck(now);
        }

        sackRetransmit(now);
        if (autoPump) {
            pump();
        }
    }

    private void onNewAck(Wire.Ack a, long now) {
        int acked = a.ack() - sndUna;

        boolean anyRetx = false;
        for (int seq = sndUna; seq < a.ack(); seq++) {
            Unacked u = buffer.remove(seq);
            if (u != null && u.retransmitted) {
                anyRetx = true;
            }
            sacked.remove(seq);
        }
        sndUna = a.ack();
        dupAckCount = 0;

        // Muestra de RTT (Karn: ignorar si hubo retransmision en el rango).
        if (!anyRetx && a.echoTs() > 0) {
            updateRtt(now - a.echoTs());
        }

        if (inRecovery) {
            if (a.ack() >= recoverPoint) {
                inRecovery = false;
                cwnd = ssthresh;                 // deflate al salir
            } else {
                cwnd = ssthresh;                 // ack parcial
                retransmitFirstHole(now);
            }
        } else {
            growCwnd(acked);
        }

        if (buffer.isEmpty()) {
            timerRunning = false;
        } else {
            restartTimer(now);
        }
    }

    private void onDuplicateAck(long now) {
        dupAckCount++;
        if (inRecovery) {
            cwnd += 1;                            // inflar durante fast recovery
        } else if (dupAckCount == DUP_ACK_THRESHOLD) {
            enterFastRecovery(now);
        }
    }

    private void growCwnd(int acked) {
        if (cwnd < ssthresh) {
            cwnd += acked;                       // slow start (exponencial)
        } else {
            cwnd += (double) acked / cwnd;       // congestion avoidance (lineal)
        }
    }

    // ---- Recuperacion ante perdida ---------------------------------------

    private void enterFastRecovery(long now) {
        ssthresh = Math.max(inFlight() / 2.0, 2.0);
        cwnd = ssthresh + DUP_ACK_THRESHOLD;
        inRecovery = true;
        recoverPoint = sndNxt;
        fastRetx++;
        retransmitFirstHole(now);
    }

    /** Retransmite el hueco mas bajo (SEQ sin confirmar ni SACK). */
    private void retransmitFirstHole(long now) {
        for (int seq = sndUna; seq < sndNxt; seq++) {
            if (!sacked.contains(seq) && buffer.containsKey(seq)) {
                transmit(seq, now, true);
                return;
            }
        }
    }

    /**
     * Retransmision guiada por SACK (RFC 6675): si hay segmentos con SACK por
     * encima, los huecos por debajo del SACK mas alto probablemente se
     * perdieron; retransmitirlos (con throttle por RTO/2 para no duplicar).
     */
    private void sackRetransmit(long now) {
        if (sacked.isEmpty()) {
            return;
        }
        int maxSacked = sacked.last();
        int budget = Math.min(window(), MAX_SACK_RETRANSMITS_PER_ACK);
        for (int seq = sndUna; seq < maxSacked && budget > 0; seq++) {
            if (sacked.contains(seq)) {
                continue;
            }
            Unacked u = buffer.get(seq);
            if (u == null) {
                continue;
            }
            // IsLost (RFC 6675): >= 3 segmentos con SACK por encima => probable
            // perdida (no mero reordenamiento). El throttle por RTO/2 evita
            // retransmitir el mismo hueco mas de una vez por RTT.
            int sackedAbove = sacked.tailSet(seq + 1).size();
            if (sackedAbove >= DUP_ACK_THRESHOLD && (now - u.lastSent) > rto / 2) {
                if (!transmit(seq, now, true)) {
                    break;
                }
                budget--;
            }
        }
    }

    // ---- Temporizador (RTO) ----------------------------------------------

    /** Debe llamarse periodicamente; dispara retransmision por timeout. */
    public void tick() {
        long now = clock.getAsLong();
        if (timerRunning && !buffer.isEmpty() && now >= rtoDeadline) {
            onTimeout(now);
            if (autoPump) {
                pump();
            }
        }
    }

    private void onTimeout(long now) {
        timeouts++;
        ssthresh = Math.max(inFlight() / 2.0, 2.0);
        cwnd = 1.0;                              // colapso a slow start
        dupAckCount = 0;
        inRecovery = false;
        rto = Math.min(rto * 2, MAX_RTO);        // backoff exponencial (Karn)
        retransmitFirstHole(now);
        restartTimer(now);
    }

    private void startTimerIfIdle(long now) {
        if (!timerRunning) {
            restartTimer(now);
        }
    }

    private void restartTimer(long now) {
        rtoDeadline = now + rto;
        timerRunning = true;
    }

    private void updateRtt(long sample) {
        if (sample < 0) {
            return;
        }
        if (srtt < 0) {
            srtt = sample;
            rttvar = sample / 2.0;
        } else {
            rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - sample);
            srtt = 0.875 * srtt + 0.125 * sample;
        }
        rto = Math.min(MAX_RTO, Math.max(MIN_RTO, (long) (srtt + 4 * rttvar)));
    }

    // ---- Telemetria ------------------------------------------------------

    public double cwnd() { return cwnd; }
    public double ssthresh() { return ssthresh; }
    public long rto() { return rto; }
    public double srtt() { return srtt; }
    public long sent() { return sent; }
    public long retransmits() { return retransmits; }
    public long timeouts() { return timeouts; }
    public long fastRetx() { return fastRetx; }
    public int sndUna() { return sndUna; }
    public int sndNxt() { return sndNxt; }
    public boolean inRecovery() { return inRecovery; }
}
