package com.cc8.server.protocol;

import com.cc8.server.image.H2kFormat;
import com.cc8.server.image.H2kReader;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** Coalesces concurrent reads of the same packet from the same H2K reader. */
public final class SharedPacketStore {
    private record Key(H2kReader reader, long offset, int length) { }

    private final ConcurrentHashMap<Key, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();

    public byte[] read(H2kReader reader, H2kFormat.PacketIndex packet, int layer) throws IOException {
        Key key = new Key(reader, packet.layerOffset()[layer], packet.layerLen()[layer]);
        CompletableFuture<byte[]> own = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inFlight.putIfAbsent(key, own);
        if (existing == null) {
            try {
                byte[] data = reader.readPacket(packet, layer);
                own.complete(data);
                return data;
            } catch (IOException | RuntimeException ex) {
                own.completeExceptionally(ex);
                throw ex;
            } finally {
                inFlight.remove(key, own);
            }
        }
        try {
            return existing.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for packet", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException io) throw io;
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Packet read failed", ex.getCause());
        }
    }
}
