package com.cc8.server.http;

import com.cc8.server.handler.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Servidor HTTP asincrono basado en {@link AsynchronousServerSocketChannel}
 * (NIO.2). Acepta conexiones sin bloquear: cada aceptacion dispara un
 * callback que a su vez vuelve a poner un accept() pendiente, de modo que el
 * servidor atiende multiples clientes concurrentemente (requisito 1) sobre un
 * pool de hilos acotado en lugar de un hilo por cliente.
 */
public final class HttpServer {

    private final int port;
    private final Router router;
    private ConnectionUpgrade upgradeHandler; // opcional (p.ej. WebSocket)
    private AsynchronousChannelGroup group;
    private AsynchronousServerSocketChannel serverChannel;

    public HttpServer(int port, Router router) {
        this.port = port;
        this.router = router;
    }

    /** Registra un manejador de upgrade de protocolo (WebSocket). */
    public HttpServer upgrade(ConnectionUpgrade handler) {
        this.upgradeHandler = handler;
        return this;
    }

    public void start() throws IOException {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        group = AsynchronousChannelGroup.withFixedThreadPool(
                threads, Executors.defaultThreadFactory());

        serverChannel = AsynchronousServerSocketChannel.open(group);
        serverChannel.bind(new InetSocketAddress(port));

        accept();

        System.out.printf("Servidor asincrono escuchando en http://localhost:%d  (hilos I/O: %d)%n",
                port, threads);
    }

    private void accept() {
        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel client, Void att) {
                // Aceptar la siguiente conexion de inmediato.
                accept();
                // Atender esta conexion.
                new HttpConnection(client, router, upgradeHandler).start();
            }

            @Override
            public void failed(Throwable exc, Void att) {
                if (serverChannel.isOpen()) {
                    accept(); // reintentar aceptando la siguiente
                }
            }
        });
    }

    /** Mantiene vivo el proceso hasta que se termine el grupo de canales. */
    public void awaitTermination() throws InterruptedException {
        group.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    public void stop() throws IOException {
        serverChannel.close();
        group.shutdown();
    }
}
