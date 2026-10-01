package com.abegomez.research.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import com.abegomez.research.common.exception.DependencyUnavailableException;
import com.abegomez.research.knowledge.localrag.FakeLocalRagServer;
import com.abegomez.research.knowledge.localrag.LocalRagKnowledgeSearchAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifica el adaptador de LocalRAG contra el contrato documentado
 * {@code GET /api/search?query=...&topK=...}, usando un servidor HTTP simulado.
 *
 * <p><b>Alcance de la prueba:</b> LocalRAG todavia no expone ese endpoint. Estos
 * tests prueban el adaptador contra el contrato, no contra LocalRAG real. Cuando
 * LocalRAG lo exponga, esta misma clase sigue siendo valida sin cambios.
 */
class LocalRagKnowledgeSearchAdapterTest extends KnowledgePortContract {

    private FakeLocalRagServer server;
    private LocalRagKnowledgeSearchAdapter adapter;

    private static final String RESPUESTA_VALIDA = """
            [
              {"documentId":"doc-1","fileName":"03-machine-learning.md","pageNumber":null,
               "chunkNumber":2,"text":"El modelo cubre patrones que las reglas no catalogan.",
               "score":0.91},
              {"documentId":"doc-2","fileName":"02-reglas-estaticas.md","pageNumber":null,
               "chunkNumber":5,"text":"Las reglas tienen una precision alta sobre patrones conocidos.",
               "score":0.78}
            ]
            """;

    @BeforeEach
    void setUp() {
        server = new FakeLocalRagServer();
        adapter = new LocalRagKnowledgeSearchAdapter(
                server.baseUrl(), "/api/search", Duration.ofSeconds(5), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Override
    public KnowledgeSearchPort port() {
        return adapter;
    }

    /**
     * El servidor simulado responde con contenido valido para las consultas del
     * contrato comun, de modo que la clase hereda esas pruebas sin adaptarlas.
     */
    @Override
    String consultaDePrueba() {
        server.respondWith(200, RESPUESTA_VALIDA);
        return "deteccion de fraude con machine learning";
    }

    @Test
    @DisplayName("Mapea la respuesta del contrato a resultados de dominio")
    void mapsContractResponse() {
        server.respondWith(200, RESPUESTA_VALIDA);

        List<KnowledgeResult> results = adapter.search("machine learning", 5);

        assertThat(results).hasSize(2);

        KnowledgeResult primero = results.get(0);
        assertThat(primero.texto())
                .isEqualTo("El modelo cubre patrones que las reglas no catalogan.");
        assertThat(primero.documentoId()).isEqualTo("doc-1");
        assertThat(primero.documentoTitulo()).isEqualTo("03-machine-learning.md");
        assertThat(primero.referencia()).isEqualTo("doc-1#chunk-2");
        assertThat(primero.puntaje()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("Envia query y topK como parametros de consulta")
    void sendsQueryAndTopK() {
        server.respondWith(200, "[]");

        adapter.search("deteccion de fraude en fintech", 7);

        assertThat(server.lastQuery()).isEqualTo("deteccion de fraude en fintech");
        assertThat(server.lastTopK()).isEqualTo("7");
    }

    @Test
    @DisplayName("Escapa correctamente una consulta con caracteres especiales")
    void encodesSpecialCharacters() {
        server.respondWith(200, "[]");

        adapter.search("reglas & umbrales? top=10", 3);

        assertThat(server.lastQuery()).isEqualTo("reglas & umbrales? top=10");
    }

    @Test
    @DisplayName("Devuelve lista vacia si no hay resultados")
    void returnsEmptyList() {
        server.respondWith(200, "[]");

        assertThat(adapter.search("tema inexistente", 5)).isEmpty();
    }

    @Test
    @DisplayName("Una consulta vacia no llama al servicio")
    void skipsCallForBlankQuery() {
        server.respondWith(200, RESPUESTA_VALIDA);

        assertThat(adapter.search("   ", 5)).isEmpty();
        assertThat(adapter.search(null, 5)).isEmpty();
    }

    @Test
    @DisplayName("Falla de forma controlada con un mensaje claro cuando el endpoint no existe")
    void failsClearlyWhenEndpointMissing() {
        server.respondWith(404, "{\"error\":\"Not Found\"}");

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("no expone el endpoint de busqueda")
                .hasMessageContaining("/api/search")
                .hasMessageContaining("knowledge.provider=pgvector");
    }

    @Test
    @DisplayName("Falla de forma controlada si el servicio no responde")
    void failsWhenServiceUnreachable() {
        server.close();

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("No se pudo contactar a LocalRAG");
    }

    @Test
    @DisplayName("Falla de forma controlada si la respuesta no cumple el contrato")
    void failsWhenResponseViolatesContract() {
        server.respondWith(200, "{\"resultado\":\"esto no es una lista\"}");

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("no cumple el contrato");
    }

    @Test
    @DisplayName("Un error del servicio se propaga como dependencia no disponible")
    void failsOnServerError() {
        server.respondWith(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("Tolera campos adicionales sin romper el mapeo")
    void toleratesUnknownFields() {
        server.respondWith(200, """
                [{"documentId":"doc-9","fileName":"extra.md","chunkNumber":1,
                  "text":"contenido","score":0.5,"campoDesconocido":"ignorar"}]
                """);

        List<KnowledgeResult> results = adapter.search("extra", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).documentoId()).isEqualTo("doc-9");
    }

    @Test
    @DisplayName("Identifica el proveedor para diagnostico")
    void reportsProviderId() {
        assertThat(adapter.providerId()).isEqualTo("localrag");
    }
}
