package com.cc8.server.protocol;

/**
 * Fuente de cargas utiles (payloads) que el emisor transmite. En produccion la
 * implementa el scheduler (Hilbert + deadline/utilidad), que decide QUE paquete
 * de imagen enviar a continuacion segun el viewport actual. Devuelve null
 * cuando no hay nada que enviar por ahora.
 */
public interface SegmentSource {
    boolean hasNext();
    byte[] next();

    /** Returns data staged but not sent so a scheduler can rebuild its queue. */
    default void requeueUnsent(byte[] payload) {
        throw new UnsupportedOperationException("source cannot requeue staged payloads");
    }
}
