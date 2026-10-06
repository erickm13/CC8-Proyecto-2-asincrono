package com.cc8.server.http;

import com.cc8.server.handler.Router;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maneja una unica conexion de cliente de forma totalmente asincrona.
 *
 * <p>El ciclo es: leer -> acumular hasta tener una peticion completa ->
 * enrutar -> escribir respuesta -> (keep-alive) volver a leer, o cerrar.
 * Ninguna operacion bloquea un hilo: las continuaciones se ejecutan en los
 * callbacks de {@link CompletionHandler}, servidos por el pool del
 * {@code AsynchronousChannelGroup}.
 */
final class HttpConnection {

    private static final int READ_BUFFER = 16 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private final AsynchronousSocketChannel channel;
    private final Router router;
    private final ConnectionUpgrade upgradeHandler; // puede ser null
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER);
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

    HttpConnection(AsynchronousSocketChannel channel, Router router,
                   ConnectionUpgrade upgradeHandler) {
        this.channel = channel;
        this.router = router;
        this.upgradeHandler = upgradeHandler;
    }

    void start() {
        readMore();
    }

    // ---- Lectura asincrona -----------------------------------------------

    private void readMore() {
        readBuffer.clear();
        channel.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void att) {
                if (bytesRead == -1) {
                    close();
                    return;
                }
                readBuffer.flip();
                byte[] chunk = new byte[readBuffer.remaining()];
                readBuffer.get(chunk);
                accumulator.write(chunk, 0, chunk.length);

                if (accumulator.size() > MAX_HEADER_BYTES + MAX_BODY_BYTES) {
                    writeAndClose(HttpResponse.badRequest("Peticion demasiado grande"));
                    return;
                }
                tryProcess();
            }

            @Override
            public void failed(Throwable exc, Void att) {
                close();
            }
        });
    }

    /** Intenta parsear una peticion completa desde lo acumulado. */
    private void tryProcess() {
        byte[] data = accumulator.toByteArray();
        int headerEnd = indexOfCrlfCrlf(data);
        if (headerEnd < 0) {
            // Cabeceras aun incompletas: seguir leyendo.
            if (data.length > MAX_HEADER_BYTES) {
                writeAndClose(HttpResponse.badRequest("Cabeceras demasiado grandes"));
                return;
            }
            readMore();
            return;
        }

        String headerText = new String(data, 0, headerEnd, StandardCharsets.US_ASCII);
        ParsedHead head = parseHead(headerText);
        if (head == null) {
            writeAndClose(HttpResponse.badRequest("Linea de peticion invalida"));
            return;
        }

        int bodyStart = headerEnd + 4;
        int contentLength = 0;
        String cl = head.headers.get("content-length");
        if (cl != null) {
            try {
                contentLength = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                writeAndClose(HttpResponse.badRequest("Content-Length invalido"));
                return;
            }
        }
        if (contentLength > MAX_BODY_BYTES) {
            writeAndClose(HttpResponse.badRequest("Cuerpo demasiado grande"));
            return;
        }

        int available = data.length - bodyStart;
        if (available < contentLength) {
            // Falta cuerpo: seguir leyendo.
            readMore();
            return;
        }

        byte[] body = new byte[contentLength];
        System.arraycopy(data, bodyStart, body, 0, contentLength);

        HttpRequest request = new HttpRequest(
                head.method, head.target, head.version, head.headers, body);

        // Upgrade de protocolo (p.ej. WebSocket): cede el canal y termina.
        if (upgradeHandler != null && upgradeHandler.handles(request)) {
            int consumed = bodyStart + contentLength;
            int extra = data.length - consumed;
            byte[] leftover = new byte[extra];
            System.arraycopy(data, consumed, leftover, 0, extra);
            accumulator.reset();
            upgradeHandler.upgrade(request, channel, leftover);
            return; // el servidor HTTP ya no gestiona esta conexion
        }

        dispatch(request);
    }

    private void dispatch(HttpRequest request) {
        HttpResponse response;
        try {
            response = router.dispatch(request);
        } catch (Exception e) {
            response = HttpResponse.serverError(e.getClass().getSimpleName());
        }

        boolean keepAlive = request.keepAlive() && response.isKeepAlive();
        response.keepAlive(keepAlive);

        // Reiniciar el acumulador para la siguiente peticion (keep-alive).
        accumulator.reset();

        if (keepAlive) {
            write(response.toByteBuffer(), this::readMore);
        } else {
            writeAndClose(response);
        }
    }

    // ---- Escritura asincrona ---------------------------------------------

    private void write(ByteBuffer buffer, Runnable onDone) {
        channel.write(buffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer written, Void att) {
                if (buffer.hasRemaining()) {
                    channel.write(buffer, null, this); // escritura parcial
                } else {
                    onDone.run();
                }
            }

            @Override
            public void failed(Throwable exc, Void att) {
                close();
            }
        });
    }

    private void writeAndClose(HttpResponse response) {
        response.keepAlive(false);
        write(response.toByteBuffer(), this::close);
    }

    private void close() {
        try {
            channel.close();
        } catch (Exception ignored) {
        }
    }

    // ---- Parseo de la cabecera -------------------------------------------

    private record ParsedHead(String method, String target, String version,
                              Map<String, String> headers) {
    }

    private static ParsedHead parseHead(String headerText) {
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
            }
        }
        return new ParsedHead(requestLine[0], requestLine[1], requestLine[2], headers);
    }

    /** Busca la secuencia \r\n\r\n que separa cabeceras de cuerpo. */
    private static int indexOfCrlfCrlf(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n'
                    && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
