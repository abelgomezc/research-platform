package com.abegomez.research.common.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.abegomez.research.knowledge.KnowledgeProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health del adaptador de LocalRAG.
 *
 * <p>Solo se registra cuando {@code app.knowledge.provider=localrag}, para no
 * reportar DOWN por un proveedor que no esta en uso.
 *
 * <p><b>Estado actual:</b> LocalRAG no expone todavia el endpoint de busqueda de
 * fragmentos. El indicador lo detecta y explica el problema en lugar de reportar
 * un fallo generico.
 */
public class LocalRagHealthIndicator implements HealthIndicator {

    private final KnowledgeProperties.LocalRag config;
    private final HttpClient httpClient;

    public LocalRagHealthIndicator(KnowledgeProperties.LocalRag config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(config.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    LocalRagHealthIndicator(KnowledgeProperties.LocalRag config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        URI probe = URI.create(stripTrailingSlash(config.getBaseUrl())
                + "/api/health");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(probe)
                    .timeout(config.getTimeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                return Health.down()
                        .withDetail("url", config.getBaseUrl())
                        .withDetail("estado", "El servicio responde HTTP " + response.statusCode())
                        .withDetail("searchPath", config.getSearchPath())
                        .build();
            }

            return searchEndpointAvailable()
                    ? Health.up()
                        .withDetail("url", config.getBaseUrl())
                        .withDetail("searchPath", config.getSearchPath())
                        .build()
                    : Health.down()
                        .withDetail("url", config.getBaseUrl())
                        .withDetail("estado", "LocalRAG responde pero no expone el endpoint de busqueda")
                        .withDetail("searchPath", config.getSearchPath())
                        .withDetail("motivo", "Se requiere un endpoint que solo recupere fragmentos "
                                + "con fuente y puntaje, sin generar respuesta con el LLM. "
                                + "Usa knowledge.provider=pgvector mientras tanto.")
                        .build();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.down(ex)
                    .withDetail("url", config.getBaseUrl())
                    .withDetail("motivo", "La comprobacion fue interrumpida")
                    .build();
        }
        catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("url", config.getBaseUrl())
                    .withDetail("searchPath", config.getSearchPath())
                    .withDetail("motivo", "LocalRAG no responde en " + probe)
                    .build();
        }
    }

    /**
     * El endpoint de busqueda existe si responde con algo distinto de 404.
     * Un 400 (entrada invalida) o un 500 por consulta vacia tambien prueban que
     * la ruta esta publicada; lo que indica que falta es el 404.
     */
    private boolean searchEndpointAvailable() {
        try {
            URI uri = URI.create(stripTrailingSlash(config.getBaseUrl()) + config.getSearchPath()
                    + "?query=healthcheck&topK=1");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(config.getTimeout())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() != 404;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        catch (Exception ex) {
            return false;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
