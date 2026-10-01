package com.abegomez.research.knowledge.localrag;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.abegomez.research.common.exception.DependencyUnavailableException;
import com.abegomez.research.knowledge.KnowledgeResult;
import com.abegomez.research.knowledge.KnowledgeSearchPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador HTTP hacia el servicio LocalRAG.
 *
 * <p>Implementa {@link KnowledgeSearchPort} contra el contrato
 * {@code GET /api/search?query=...&topK=...}, que devuelve una lista de
 * {@link LocalRagSearchItem}. Solo recuperacion: no invoca al LLM, no reescribe
 * la consulta y no toca el historial de conversacion de LocalRAG.
 *
 * <p><b>Estado real:</b> LocalRAG todavia no expone ese endpoint. Cuando se activa
 * este proveedor, las llamadas fallan con
 * {@link DependencyUnavailableException} y un mensaje que explica el problema, y el
 * health indicator queda en DOWN. El adaptador no inventa endpoints ni degrada a
 * una busqueda que no cumple el contrato.
 */
public class LocalRagKnowledgeSearchAdapter implements KnowledgeSearchPort {

    private static final Logger log = LoggerFactory.getLogger(LocalRagKnowledgeSearchAdapter.class);

    private final String baseUrl;
    private final String searchPath;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final java.time.Duration timeout;

    public LocalRagKnowledgeSearchAdapter(String baseUrl, String searchPath,
                                          java.time.Duration timeout, ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.searchPath = searchPath.startsWith("/") ? searchPath : "/" + searchPath;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<KnowledgeResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        URI uri = URI.create(baseUrl + searchPath
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&topK=" + Math.max(1, topK));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException(
                    "La busqueda en LocalRAG fue interrumpida", ex);
        }
        catch (Exception ex) {
            throw new DependencyUnavailableException(
                    "No se pudo contactar a LocalRAG en " + uri + ": " + ex.getMessage()
                            + ". Verifica que este proyecto este levantado y que "
                            + "app.knowledge.localrag.base-url apunte a el.", ex);
        }

        if (response.statusCode() == 404) {
            throw new DependencyUnavailableException(
                    "LocalRAG no expone el endpoint de busqueda " + searchPath
                            + " (HTTP 404). Este proyecto requiere un endpoint que solo recupere"
                            + " fragmentos con fuente y puntaje, sin generar respuesta con el LLM."
                            + " Mientras tanto usa knowledge.provider=pgvector.");
        }

        if (response.statusCode() / 100 != 2) {
            throw new DependencyUnavailableException(
                    "LocalRAG respondio HTTP " + response.statusCode() + " a " + uri);
        }

        List<LocalRagSearchItem> items;
        try {
            items = LocalRagSearchItem.emptyIfNull(
                    objectMapper.readValue(response.body(),
                            objectMapper.getTypeFactory()
                                    .constructCollectionType(List.class, LocalRagSearchItem.class)));
        }
        catch (Exception ex) {
            throw new DependencyUnavailableException(
                    "LocalRAG devolvio una respuesta que no cumple el contrato de busqueda: "
                            + ex.getMessage(), ex);
        }

        log.debug("LocalRAG devolvio {} fragmentos para la consulta", items.size());
        return items.stream().map(LocalRagSearchItem::toResult).toList();
    }

    @Override
    public String providerId() {
        return "localrag";
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
