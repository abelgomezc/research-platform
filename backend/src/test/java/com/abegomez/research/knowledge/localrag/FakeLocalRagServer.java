package com.abegomez.research.knowledge.localrag;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP simulado que reproduce el contrato de busqueda de LocalRAG.
 *
 * <p>Existe porque LocalRAG <b>aun no expone</b> {@code GET /api/search}. El
 * adaptador se verifica contra este servidor, no contra un servicio real, y eso
 * queda documentado como limitacion del alcance de la prueba.
 *
 * <p>No se usa ninguna dependencia externa: {@code com.sun.net.httpserver} viene en
 * la JDK.
 */
public class FakeLocalRagServer implements AutoCloseable {

    private final HttpServer server;
    private volatile int statusCode = 200;
    private volatile String responseBody = "[]";
    private volatile String lastQuery;
    private volatile String lastTopK;

    public FakeLocalRagServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException("No se pudo iniciar el servidor simulado", ex);
        }
        server.createContext("/api/search", this::handleSearch);
        server.createContext("/api/health", this::handleHealth);
        server.setExecutor(null);
        server.start();
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"metodo no permitido\"}");
            return;
        }

        String query = exchange.getRequestURI().getRawQuery();
        lastQuery = paramOf(query, "query");
        lastTopK = paramOf(query, "topK");

        respond(exchange, statusCode, responseBody);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"status\":\"UP\",\"ollamaStatus\":\"UP\",\"databaseStatus\":\"UP\"}");
    }

    private static String paramOf(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0 && pair.substring(0, index).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public URI searchUri() {
        return URI.create(baseUrl() + "/api/search");
    }

    public FakeLocalRagServer respondWith(int status, String body) {
        this.statusCode = status;
        this.responseBody = body;
        return this;
    }

    public String lastQuery() {
        return lastQuery;
    }

    public String lastTopK() {
        return lastTopK;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
