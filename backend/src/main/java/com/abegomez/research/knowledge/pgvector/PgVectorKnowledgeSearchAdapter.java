package com.abegomez.research.knowledge.pgvector;

import java.util.List;

import com.abegomez.research.common.exception.BusinessException;
import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.knowledge.KnowledgeResult;
import com.abegomez.research.knowledge.KnowledgeSearchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Implementacion por defecto de {@link KnowledgeSearchPort}: busqueda semantica
 * simple con pgvector.
 *
 * <p>Deliberadamente sin hybrid search, sin reranking y sin CRAG: esos
 * mecanismos pertenecen al proyecto LocalRAG y este proyecto solo necesita
 * recuperar fragmentos por similitud.
 */
public class PgVectorKnowledgeSearchAdapter implements KnowledgeSearchPort {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeSearchAdapter.class);

    private final DocumentChunkRepository repository;
    private final EmbeddingModel embeddingModel;
    private final int defaultTopK;

    public PgVectorKnowledgeSearchAdapter(DocumentChunkRepository repository,
                                          EmbeddingModel embeddingModel,
                                          int defaultTopK) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.defaultTopK = defaultTopK;
    }

    @Override
    public List<KnowledgeResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int limit = topK > 0 ? topK : defaultTopK;
        float[] embedding;
        try {
            embedding = embeddingModel.embed(query);
        }
        catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "No se pudo generar el embedding de la consulta con el modelo local: " + ex.getMessage()
                            + ". Verifica que Ollama este corriendo y que el modelo de embeddings"
                            + " este descargado.", ex);
        }

        if (embedding == null || embedding.length == 0) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "El modelo de embeddings devolvio un vector vacio");
        }

        List<DocumentChunkRepository.FragmentMatch> matches =
                repository.similaritySearch(DocumentChunkRepository.toVectorLiteral(embedding), limit);

        log.debug("Busqueda pgvector: {} fragmentos para topK={}", matches.size(), limit);

        return matches.stream()
                .map(match -> new KnowledgeResult(
                        match.texto(),
                        String.valueOf(match.documentoId()),
                        match.nombre(),
                        match.nombre() + "#doc-" + match.documentoId(),
                        match.puntaje()))
                .toList();
    }

    @Override
    public String providerId() {
        return "pgvector";
    }
}
