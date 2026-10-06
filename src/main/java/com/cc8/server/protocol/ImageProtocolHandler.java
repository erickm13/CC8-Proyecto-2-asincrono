package com.cc8.server.protocol;

import com.cc8.server.image.H2kReader;
import com.cc8.server.ws.WebSocketHandler;
import com.cc8.server.ws.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Une el transporte RAPID con el WebSocket real. Por cada conexion crea un
 * {@link Scheduler} (que lee del {@link H2kReader} compartido) y un
 * {@link ReliableSender}. Un temporizador comun invoca {@code tick()}/{@code
 * pump()} de cada sesion para gestionar RTO y avanzar la ventana.
 *
 * <p>Mensajes del cliente:
 * <ul>
 *   <li>ACK (3): realimenta al emisor (ACK acumulativo + SACK + rwnd).</li>
 *   <li>VIEWPORT (4): fija la zona/zoom visibles -> repriorizacion.</li>
 *   <li>FIN (7): cierre.</li>
 * </ul>
 */
public final class ImageProtocolHandler implements WebSocketHandler {

    private static final long TICK_MS = 20;

    private final H2kReader reader;
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rapid-ticker");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<WebSocketSession, Session> sessions = new ConcurrentHashMap<>();

    public ImageProtocolHandler(H2kReader reader) {
        this.reader = reader;
        ticker.scheduleAtFixedRate(this::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Estado por conexion. El lock serializa el acceso al emisor/scheduler. */
    private static final class Session {
        final Scheduler scheduler;
        final ReliableSender sender;
        final Object lock = new Object();

        Session(Scheduler scheduler, ReliableSender sender) {
            this.scheduler = scheduler;
            this.sender = sender;
        }
    }

    @Override
    public void onOpen(WebSocketSession ws) {
        Scheduler scheduler = new Scheduler(reader);
        ReliableSender sender = new ReliableSender(
                ws::sendBinary, scheduler, System::currentTimeMillis);
        sessions.put(ws, new Session(scheduler, sender));
    }

    @Override
    public void onBinary(WebSocketSession ws, byte[] data) {
        Session s = sessions.get(ws);
        if (s == null || data.length == 0) {
            return;
        }
        byte type = Wire.type(data);
        synchronized (s.lock) {
            switch (type) {
                case Wire.T_ACK -> s.sender.onAck(data);
                case Wire.T_VIEWPORT -> {
                    int p = 1;
                    int x = Wire.getInt(data, p); p += 4;
                    int y = Wire.getInt(data, p); p += 4;
                    int w = Wire.getInt(data, p); p += 4;
                    int h = Wire.getInt(data, p); p += 4;
                    int zoom = data[p] & 0xFF;
                    int maxLevel = Math.min(zoom, reader.header().levels());
                    s.scheduler.setViewport(x, y, w, h, maxLevel, 999);
                    s.sender.pump();
                }
                case Wire.T_FORGET -> {
                    int count = Wire.getShort(data, 1);
                    for (int i = 0; i < count; i++) {
                        s.scheduler.forget(Wire.getInt(data, 3 + i * 4));
                    }
                }
                case Wire.T_FIN -> ws.sendBinary(new byte[]{Wire.T_FIN});
                default -> { /* ignorar */ }
            }
        }
    }

    @Override
    public void onClose(WebSocketSession ws) {
        sessions.remove(ws);
    }

    private void tickAll() {
        for (Session s : sessions.values()) {
            synchronized (s.lock) {
                try {
                    s.sender.tick();
                    s.sender.pump();
                } catch (RuntimeException ignored) {
                    // una sesion con error no debe tumbar el temporizador
                }
            }
        }
    }
}
