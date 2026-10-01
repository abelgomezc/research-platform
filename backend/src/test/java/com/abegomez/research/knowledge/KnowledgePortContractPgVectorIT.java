package com.abegomez.research.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.abegomez.research.TestBeansConfiguration;
import com.abegomez.research.PostgresContainerConfiguration;
import com.abegomez.research.knowledge.pgvector.DocumentChunkRepository;
import com.abegomez.research.knowledge.pgvector.DocumentIngestionService;
import com.abegomez.research.knowledge.pgvector.PgVectorKnowledgeSearchAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Contrato del puerto de conocimiento contra pgvector real, con PostgreSQL y
 * pgvector en un contenedor.
 *
 * <p>El modelo de embeddings se sustituye por un doble determinista: lo que se
 * prueba aqui es el almacenamiento vectorial y la busqueda por similitud, no el
 * modelo. La calidad semantica se verifica en la evaluacion (Fase 10).
 *
 * <p>Requiere Docker. Si no esta disponible, el test se omite.
 */
@SpringBootTest(classes = {com.abegomez.research.ResearchPlatformApplication.class,
        TestBeansConfiguration.class, PostgresContainerConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.knowledge.provider=pgvector",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "app.demo-corpus.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class KnowledgePortContractPgVectorIT extends KnowledgePortContract {

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PgVectorKnowledgeSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM fragmentos_documento");
        jdbcTemplate.update("DELETE FROM documentos");

        repository.saveDocument("03-machine-learning.md", "MD",
                "El modelo cubre patrones que las reglas no catalogan. " +
                        "Los modelos supervisados aprenden de transacciones etiquetadas. " +
                        "El tiempo de deteccion promedio declarado es de 420 milisegundos.",
                "hash-contrato-1");
        repository.saveDocument("02-reglas-estaticas.md", "MD",
                "Las reglas tienen precision alta sobre patrones ya conocidos. " +
                        "El tiempo de deteccion promedio declarado es de 95 milisegundos.",
                "hash-contrato-2");

        adapter = new PgVectorKnowledgeSearchAdapter(repository, embeddingModel, 5);
    }

    @Override
    public KnowledgeSearchPort port() {
        return adapter;
    }

    @Test
    @DisplayName("Guarda y recupera fragmentos reales desde pgvector")
    void roundTripSobrePgVector() {
        var ingesta = new DocumentIngestionService(repository, embeddingModel, new com.abegomez.research
                .knowledge.pgvector.TextChunker(400, 60));

        ingesta.ingest("contrato.md", "MD", contenidoDePrueba());

        List<KnowledgeResult> resultados = adapter.search(contenidoDePrueba(), 3);

        assertThat(resultados).isNotEmpty();
        assertThat(resultados.get(0).texto()).contains("fraude");
        assertThat(resultados.get(0).documentoTitulo()).isEqualTo("contrato.md");
    }

    @Test
    @DisplayName("La ingesta es idempotente por hash de contenido")
    void ingestionIsIdempotent() {
        var ingesta = new DocumentIngestionService(repository, embeddingModel, new com.abegomez.research
                .knowledge.pgvector.TextChunker(400, 60));

        var primero = ingesta.ingest("idempotente.md", "MD", contenidoDePrueba());
        var segundo = ingesta.ingest("idempotente.md", "MD", contenidoDePrueba());

        assertThat(primero.yaExistia()).isFalse();
        assertThat(segundo.yaExistia()).isTrue();
        assertThat(segundo.documentoId()).isEqualTo(primero.documentoId());
        assertThat(repository.countDocuments()).isEqualTo(1);
    }

    @Test
    @DisplayName("Al borrar un documento se borran sus fragmentos en cascada")
    void deletingDocumentRemovesChunks() {
        var ingesta = new DocumentIngestionService(repository, embeddingModel, new com.abegomez.research
                .knowledge.pgvector.TextChunker(400, 60));
        var resultado = ingesta.ingest("cascada.md", "MD", contenidoDePrueba());
        assertThat(repository.countFragments(resultado.documentoId())).isPositive();

        ingesta.delete(resultado.documentoId());

        assertThat(repository.countFragments(resultado.documentoId())).isZero();
    }

    private String contenidoDePrueba() {
        return "La deteccion de fraude en fintech latinoamericanas combina reglas estaticas "
                + "y modelos de machine learning. El analisis de grafo aporta deteccion de redes. "
                + "La biometria conductual usa el comportamiento del usuario como senal de riesgo. "
                + "La deteccion de anomalias identifica patrones sin etiqueta previa.";
    }
}
