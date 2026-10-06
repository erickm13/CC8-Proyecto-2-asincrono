package com.cc8.server;

import com.cc8.server.handler.Router;
import com.cc8.server.handler.StaticFileHandler;
import com.cc8.server.http.HttpResponse;
import com.cc8.server.http.HttpServer;
import com.cc8.server.image.H2kFormat;
import com.cc8.server.image.H2kReader;
import com.cc8.server.protocol.ImageProtocolHandler;
import com.cc8.server.ws.WebSocketEndpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Punto de entrada del servidor asincrono del Proyecto de CC VIII.
 *
 * <p>Expone:
 * <ul>
 *   <li>Archivos estaticos del sitio web (webroot {@code public/}).</li>
 *   <li>{@code GET /health} sonda de estado.</li>
 *   <li>{@code GET /api/manifest} metadatos de la imagen (JSON).</li>
 *   <li>{@code /stream} WebSocket con el protocolo RAPID.</li>
 * </ul>
 *
 * Uso: java com.cc8.server.Main [puerto] [webroot] [imagen.h2k]
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path webRoot = Paths.get(args.length > 1 ? args[1] : "public");
        Path imagePath = Paths.get(args.length > 2 ? args[2] : "images/sample.h2k");

        StaticFileHandler staticFiles = new StaticFileHandler(webRoot);
        Router router = new Router()
                .get("/health", req -> HttpResponse.ok().text("OK"));

        HttpServer server = new HttpServer(port, router);

        if (Files.exists(imagePath)) {
            H2kReader reader = new H2kReader(imagePath);
            H2kFormat.Header h = reader.header();
            router.get("/api/manifest", req -> HttpResponse.ok()
                    .body(manifestJson(h, imagePath).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "application/json; charset=utf-8"));
            server.upgrade(new WebSocketEndpoint("/stream", new ImageProtocolHandler(reader)));
            System.out.printf("Imagen cargada: %s  (%dx%d, %d componentes, %d niveles)%n",
                    imagePath, h.width(), h.height(), h.components(), h.levels());
        } else {
            System.out.printf("AVISO: no existe %s. Genera un .h2k con Preprocessor. "
                    + "El sitio se sirve, pero /stream y /api/manifest estan inactivos.%n", imagePath);
        }

        router.fallback(staticFiles); // todo lo demas: archivos del sitio
        server.start();
        server.awaitTermination();
    }

    private static String manifestJson(H2kFormat.Header h, Path path) {
        String name = path.getFileName().toString();
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"width\":" + h.width() + ","
                + "\"height\":" + h.height() + ","
                + "\"components\":" + h.components() + ","
                + "\"tileSize\":" + h.tileSize() + ","
                + "\"levels\":" + h.levels() + ","
                + "\"precinct\":" + h.precinct() + ","
                + "\"tilesX\":" + h.tilesX() + ","
                + "\"tilesY\":" + h.tilesY()
                + "}";
    }
}
