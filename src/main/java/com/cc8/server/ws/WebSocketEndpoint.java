package com.cc8.server.ws;

import com.cc8.server.http.ConnectionUpgrade;
import com.cc8.server.http.HttpRequest;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * Adapta un {@link WebSocketHandler} a un punto de upgrade HTTP en una ruta
 * concreta. Realiza el handshake (101) y arranca la sesion.
 */
public final class WebSocketEndpoint implements ConnectionUpgrade {

    private final String path;
    private final WebSocketHandler handler;

    public WebSocketEndpoint(String path, WebSocketHandler handler) {
        this.path = path;
        this.handler = handler;
    }

    @Override
    public boolean handles(HttpRequest request) {
        return request.path().equals(path) && WebSocketHandshake.isUpgrade(request);
    }

    @Override
    public void upgrade(HttpRequest request, AsynchronousSocketChannel channel, byte[] leftover) {
        byte[] response = WebSocketHandshake.responseBytes(request.header("sec-websocket-key"));
        ByteBuffer buf = ByteBuffer.wrap(response);
        channel.write(buf, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer n, Void att) {
                if (buf.hasRemaining()) {
                    channel.write(buf, null, this);
                    return;
                }
                WebSocketSession session = new WebSocketSession(channel, handler);
                handler.onOpen(session);
                session.start(leftover);
            }

            @Override
            public void failed(Throwable exc, Void att) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                }
            }
        });
    }
}
