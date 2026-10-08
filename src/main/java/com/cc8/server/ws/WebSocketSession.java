package com.cc8.server.ws;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;

/**
 * Sesion WebSocket sobre un canal asincrono. Lee frames sin bloquear, maneja
 * ping/pong y cierre, reensambla frames fragmentados, y serializa las
 * escrituras salientes mediante una cola (el I/O asincrono no admite dos
 * escrituras simultaneas sobre el mismo canal).
 */
public final class WebSocketSession {

    private static final int READ_BUFFER = 16 * 1024;
    private static final int MAX_QUEUED_BYTES = 1 << 20;

    private final AsynchronousSocketChannel channel;
    private final WebSocketHandler handler;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER);
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

    // Reensamblado de fragmentos.
    private int fragmentOpcode = -1;
    private final ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();

    // Cola de escritura.
    private final Object writeLock = new Object();
    private final ArrayDeque<ByteBuffer> writeQueue = new ArrayDeque<>();
    private int queuedBytes;
    private boolean writing = false;
    private volatile boolean closed = false;

    public WebSocketSession(AsynchronousSocketChannel channel, WebSocketHandler handler) {
        this.channel = channel;
        this.handler = handler;
    }

    void start(byte[] leftover) {
        if (leftover != null && leftover.length > 0) {
            accumulator.writeBytes(leftover);
            processFrames();
        }
        readMore();
    }

    // ---- API de envio ----------------------------------------------------

    public void sendBinary(byte[] payload) {
        if (!trySendBinary(payload)) {
            doClose();
        }
    }

    /** Queues binary data when bounded writer capacity is available. */
    public boolean trySendBinary(byte[] payload) {
        if (payload.length > MAX_QUEUED_BYTES - 10) {
            return false;
        }
        return tryEnqueue(WebSocketFrame.binary(payload));
    }

    public void sendText(String text) {
        enqueue(WebSocketFrame.encode(WebSocketFrame.OP_TEXT,
                text.getBytes(StandardCharsets.UTF_8), true));
    }

    public void sendPing(byte[] payload) {
        enqueue(WebSocketFrame.encode(WebSocketFrame.OP_PING, payload, true));
    }

    public boolean isClosed() {
        return closed;
    }

    // ---- Lectura ---------------------------------------------------------

    private void readMore() {
        readBuffer.clear();
        channel.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer n, Void att) {
                if (n == -1) {
                    doClose();
                    return;
                }
                readBuffer.flip();
                byte[] chunk = new byte[readBuffer.remaining()];
                readBuffer.get(chunk);
                accumulator.writeBytes(chunk);
                processFrames();
                if (!closed) {
                    readMore();
                }
            }

            @Override
            public void failed(Throwable exc, Void att) {
                doClose();
            }
        });
    }

    private void processFrames() {
        byte[] data = accumulator.toByteArray();
        int off = 0;
        while (true) {
            WebSocketFrame.ParseResult r =
                    WebSocketFrame.tryParse(data, off, data.length - off);
            if (r == null) {
                break;
            }
            handleFrame(r.frame());
            off += r.consumed();
            if (closed) {
                return;
            }
        }
        accumulator.reset();
        if (off < data.length) {
            accumulator.write(data, off, data.length - off);
        }
    }

    private void handleFrame(WebSocketFrame.Frame frame) {
        switch (frame.opcode()) {
            case WebSocketFrame.OP_CLOSE -> {
                enqueue(WebSocketFrame.close());
                doClose();
            }
            case WebSocketFrame.OP_PING -> enqueue(WebSocketFrame.pong(frame.payload()));
            case WebSocketFrame.OP_PONG -> handler.onPong(this, frame.payload());
            case WebSocketFrame.OP_BINARY, WebSocketFrame.OP_TEXT -> {
                if (frame.fin()) {
                    deliver(frame.opcode(), frame.payload());
                } else {
                    fragmentOpcode = frame.opcode();
                    fragmentBuffer.reset();
                    fragmentBuffer.writeBytes(frame.payload());
                }
            }
            case WebSocketFrame.OP_CONTINUATION -> {
                fragmentBuffer.writeBytes(frame.payload());
                if (frame.fin()) {
                    deliver(fragmentOpcode, fragmentBuffer.toByteArray());
                    fragmentOpcode = -1;
                    fragmentBuffer.reset();
                }
            }
            default -> { /* opcode reservado: ignorar */ }
        }
    }

    private void deliver(int opcode, byte[] payload) {
        if (opcode == WebSocketFrame.OP_TEXT) {
            handler.onText(this, new String(payload, StandardCharsets.UTF_8));
        } else {
            handler.onBinary(this, payload);
        }
    }

    // ---- Escritura serializada ------------------------------------------

    private void enqueue(byte[] frameBytes) {
        if (!tryEnqueue(frameBytes)) doClose();
    }

    private boolean tryEnqueue(byte[] frameBytes) {
        ByteBuffer buf = ByteBuffer.wrap(frameBytes);
        synchronized (writeLock) {
            if (closed || frameBytes.length > MAX_QUEUED_BYTES - queuedBytes) {
                return false;
            }
            writeQueue.add(buf);
            queuedBytes += frameBytes.length;
            if (writing) {
                return true;
            }
            writing = true;
        }
        writeNext();
        return true;
    }

    private void writeNext() {
        if (closed) return;
        ByteBuffer buf;
        synchronized (writeLock) {
            if (closed) {
                writing = false;
                return;
            }
            buf = writeQueue.peek();
            if (buf == null) {
                writing = false;
                return;
            }
        }
        channel.write(buf, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer n, Void att) {
                if (buf.hasRemaining()) {
                    channel.write(buf, null, this);
                    return;
                }
                synchronized (writeLock) {
                    if (writeQueue.peek() == buf) {
                        writeQueue.poll();
                        queuedBytes -= buf.capacity();
                    }
                }
                writeNext();
            }

            @Override
            public void failed(Throwable exc, Void att) {
                doClose();
            }
        });
    }

    private void doClose() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            closed = true;
            writeQueue.clear();
            queuedBytes = 0;
            writing = false;
        }
        try {
            channel.close();
        } catch (Exception ignored) {
        }
        handler.onClose(this);
    }
}
