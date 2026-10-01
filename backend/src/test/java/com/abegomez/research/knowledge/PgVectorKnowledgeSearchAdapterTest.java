package com.abegomez.research.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.common.exception.BusinessException;
import com.abegomez.research.knowledge.pgvector.DocumentChunkRepository;
import com.abegomez.research.knowledge.pgvector.PgVectorKnowledgeSearchAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Adaptador pgvector con dobles de repositorio y modelo de embeddings.
 *
 * <p>La prueba real contra PostgreSQL con pgvector vive en
 * {@code KnowledgePortContractPgVectorIT}, que requiere Docker.
 */
class PgVectorKnowledgeSearchAdapterTest extends KnowledgePortContract {

    private DocumentChunkRepository repository;
    private EmbeddingModel embeddingModel;
    private PgVectorKnowledgeSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentChunkRepository.class);
        embeddingModel = mock(EmbeddingModel.class);

        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(repository.similaritySearch(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        new DocumentChunkRepository.FragmentMatch("texto relevante", 7L,
                                "03-machine-learning.md", 0.18),
                        new DocumentChunkRepository.FragmentMatch("texto secundario", 8L,
                                "02-reglas-estaticas.md", 0.52)));

        adapter = new PgVectorKnowledgeSearchAdapter(repository, embeddingModel, 5);
    }

    @Override
    public KnowledgeSearchPort port() {
        return adapter;
    }

    @Test
    @DisplayName("Convierte la distancia coseno en un puntaje normalizado")
    void normalizesCosineDistance() {
        List<KnowledgeResult> results = adapter.search("fraude", 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).puntaje()).isEqualTo(0.91);
        assertThat(results.get(1).puntaje()).isEqualTo(0.74);
    }

    @Test
    @DisplayName("Construye una referencia citable con el id del documento")
    void buildsCitableReference() {
        KnowledgeResult primero = adapter.search("fraude", 5).get(0);

        assertThat(primero.referencia()).isEqualTo("03-machine-learning.md#doc-7");
        assertThat(primero.documentoId()).isEqualTo("7");
        assertThat(primero.documentoTitulo()).isEqualTo("03-machine-learning.md");
    }

    @Test
    @DisplayName("Usa el topK por defecto cuando se pide cero")
    void usesDefaultTopK() {
        var repositorio = mock(DocumentChunkRepository.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1f});
        when(repositorio.similaritySearch(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(5))).thenReturn(List.of());

        new PgVectorKnowledgeSearchAdapter(repositorio, embeddingModel, 5).search("x", 0);

        org.mockito.Mockito.verify(repositorio).similaritySearch(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    @DisplayName("Falla con un mensaje accionable si el modelo de embeddings falla")
    void failsClearlyWhenEmbeddingModelUnavailable() {
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No se pudo generar el embedding")
                .hasMessageContaining("Ollama")
                .hasMessageContaining("modelo de embeddings");
    }

    @Test
    @DisplayName("Falla si el modelo de embeddings devuelve un vector vacio")
    void failsOnEmptyEmbedding() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[0]);

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vector vacio");
    }

    @Test
    @DisplayName("El error de embeddings usa el codigo de dependencia no disponible")
    void usesDependencyUnavailableCode() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("caido"));

        assertThatThrownBy(() -> adapter.search("fraude", 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
