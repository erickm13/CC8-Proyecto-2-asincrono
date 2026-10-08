package com.cc8.server.protocol;

import com.cc8.server.image.H2kReader;
import com.cc8.server.image.ZipPyramidReader;
import com.cc8.server.ws.WebSocketHandler;
import com.cc8.server.ws.WebSocketSession;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Joins RAPID schedulers and reliable senders to the WebSocket transport. */
public final class ImageProtocolHandler implements WebSocketHandler {

    private static final long TICK_MS = 20;
    private static final int FAIR_QUANTUM = Wire.MAX_BATCH_PAYLOAD + 64;
    private static final int MAX_DISPATCH_BATCHES = 2048;
    private static final int LOAD_SESSION_CAP = 16;

    private final H2kReader reader;
    private final ZipPyramidReader zipReader;
    private final SharedPacketStore packetStore = new SharedPacketStore();
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rapid-ticker");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<WebSocketSession, Session> sessions = new ConcurrentHashMap<>();
    private final Object dispatchLock = new Object();
    private volatile double loadEstimate;

    public ImageProtocolHandler(H2kReader reader) {
        this.reader = reader;
        this.zipReader = null;
        ticker.scheduleAtFixedRate(this::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public ImageProtocolHandler(ZipPyramidReader reader) {
        this.reader = null;
        this.zipReader = reader;
        ticker.scheduleAtFixedRate(this::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private static final class Session {
        final RapidScheduler scheduler;
        final ReliableSender sender;
        final Object lock = new Object();
        int mode;
        int deficit;
        double load = -1;
        boolean negotiated;

        Session(RapidScheduler scheduler, ReliableSender sender) {
            this.scheduler = scheduler;
            this.sender = sender;
        }
    }

    @Override
    public void onOpen(WebSocketSession ws) {
        RapidScheduler scheduler = zipReader == null
                ? new Scheduler(reader, packetStore) : new ZipPyramidScheduler(zipReader);
        Link link = new Link() {
            @Override
            public void send(byte[] datagram) {
                ws.sendBinary(datagram);
            }

            @Override
            public boolean trySend(byte[] datagram) {
                return ws.trySendBinary(datagram);
            }
        };
        ReliableSender sender = new ReliableSender(link, scheduler, System::currentTimeMillis, false);
        sessions.put(ws, new Session(scheduler, sender));
        refreshLoad();
    }

    @Override
    public void onBinary(WebSocketSession ws, byte[] data) {
        Session s = sessions.get(ws);
        if (s == null || data == null || data.length == 0) {
            return;
        }
        boolean dispatch = false;
        synchronized (s.lock) {
            switch (Wire.type(data)) {
                case Wire.T_HELLO -> {
                    if (data.length != 3 || (data[1] & 0xFF) != (zipReader == null ? 1 : 2)) {
                        return;
                    }
                    int mode = data[2] & 0xFF;
                    if (mode > 2) {
                        return;
                    }
                    s.negotiated = true;
                    if (s.mode != mode) {
                        s.sender.discardStaged();
                        s.scheduler.setMode(mode);
                        s.mode = mode;
                    }
                    s.sender.setBatchingEnabled(mode != 0);
                    dispatch = true;
                }
                case Wire.T_ACK -> {
                    if (zipReader != null && !s.negotiated) return;
                    if (!Wire.isValidAck(data)) {
                        return;
                    }
                    s.sender.onAck(data);
                    dispatch = true;
                }
                case Wire.T_VIEWPORT -> {
                    if (zipReader != null && !s.negotiated) return;
                    if (data.length != 18 && data.length != 21) {
                        return;
                    }
                    int p = 1;
                    int x = Wire.getInt(data, p); p += 4;
                    int y = Wire.getInt(data, p); p += 4;
                    int w = Wire.getInt(data, p); p += 4;
                    int h = Wire.getInt(data, p); p += 4;
                    int maxLevel = Math.min(data[p] & 0xFF,
                            zipReader == null ? reader.header().levels() : zipReader.maxLevel());
                    if (x < 0 || y < 0 || w <= 0 || h <= 0
                            || (long) x + w > Integer.MAX_VALUE
                            || (long) y + h > Integer.MAX_VALUE) {
                        return;
                    }
                    double dx = 0, dy = 0, confidence = 0;
                    if (data.length == 21) {
                        dx = Math.max(-1, data[18] / 127.0);
                        dy = Math.max(-1, data[19] / 127.0);
                        confidence = (data[20] & 0xFF) / 255.0;
                    }
                    s.sender.discardStaged();
                    s.scheduler.setViewport(x, y, w, h, maxLevel, 999,
                            dx, dy, confidence, loadEstimate);
                    dispatch = true;
                }
                case Wire.T_FORGET -> {
                    if (zipReader != null && !s.negotiated) return;
                    if (data.length < 3) {
                        return;
                    }
                    int count = Wire.getShort(data, 1);
                    if (data.length != 3 + count * 4) {
                        return;
                    }
                    for (int i = 0; i < count; i++) {
                        s.scheduler.forget(Wire.getInt(data, 3 + i * 4));
                    }
                    dispatch = true;
                }
                case Wire.T_FIN -> {
                    if (data.length == 1) {
                        ws.sendBinary(new byte[]{Wire.T_FIN});
                    }
                }
                default -> { /* unknown control type */ }
            }
        }
        if (dispatch) {
            dispatchOneRound();
        }
    }

    @Override
    public void onClose(WebSocketSession ws) {
        if (sessions.remove(ws) != null) {
            refreshLoad();
        }
    }

    private void tickAll() {
        for (Session s : sessions.values()) {
            synchronized (s.lock) {
                try {
                    s.sender.tick();
                } catch (RuntimeException ignored) {
                    // Keep one broken session from stopping the shared timer.
                }
            }
        }
        refreshLoad();
        dispatchOneRound();
    }

    private void refreshLoad() {
        double load = Math.min(1.0, sessions.size() / (double) LOAD_SESSION_CAP);
        loadEstimate = load;
        for (Session s : sessions.values()) {
            synchronized (s.lock) {
                if (s.load != load) {
                    s.scheduler.setLoad(load);
                    s.load = load;
                }
            }
        }
    }

    /** One bounded DRR round gives each backlogged session the same byte budget. */
    private void dispatchOneRound() {
        synchronized (dispatchLock) {
            for (Session s : new ArrayList<>(sessions.values())) {
                synchronized (s.lock) {
                    try {
                        if (!s.sender.canPump()) {
                            continue;
                        }
                        s.deficit = Math.min(Integer.MAX_VALUE - FAIR_QUANTUM,
                                s.deficit) + FAIR_QUANTUM;
                        for (int sent = 0; sent < MAX_DISPATCH_BATCHES && s.sender.canPump(); sent++) {
                            int nextBytes = s.sender.nextWireBytes();
                            if (nextBytes <= 0 || nextBytes > s.deficit) {
                                break;
                            }
                            int sentBytes = s.sender.pumpOne(s.deficit);
                            if (sentBytes <= 0) {
                                s.deficit = 0;
                                break;
                            }
                            s.deficit -= sentBytes;
                        }
                    } catch (RuntimeException ignored) {
                        // Session work stays bounded and isolated from the round.
                    }
                }
            }
        }
    }
}
