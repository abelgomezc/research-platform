package com.abegomez.research.knowledge.pgvector;

import java.util.ArrayList;
import java.util.List;

import com.abegomez.research.common.exception.BusinessException;
import com.abegomez.research.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Ingesta de documentos en el conocimiento interno con pgvector.
 *
 * <p>Flujo: extraer texto, dividir en fragmentos con solape, generar embeddings y
 * guardar. Si el contenido ya existe (mismo hash) no se vuelve a procesar, para
 * que cargar el corpus de demostracion varias veces sea idempotente.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final DocumentChunkRepository repository;
    private final EmbeddingModel embeddingModel;
    private final TextChunker chunker;

    public DocumentIngestionService(DocumentChunkRepository repository,
                                    EmbeddingModel embeddingModel,
                                    TextChunker chunker) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.chunker = chunker;
    }

    /**
     * Resultado de una ingesta.
     *
     * @param documentoId    id del documento almacenado
     * @param fragmentos    numero de fragmentos generados
     * @param yaExistia     true si el contenido ya estaba cargado
     */
    public record IngestionResult(long documentoId, int fragmentos, boolean yaExistia) {
    }

    public IngestionResult ingest(String nombre, String tipo, String contenido) {
        if (contenido == null || contenido.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "El documento '" + nombre + "' no tiene texto que ingerir");
        }

        String hash = ContentHash.sha256(nombre + "|" + contenido);
        if (repository.existsByHash(hash)) {
            log.debug("Documento ya ingerido, se omite: {}", nombre);
            long id = repository.findByHash(hash);
            return new IngestionResult(id, repository.countFragments(id), true);
        }

        List<String> fragmentos = chunker.chunk(contenido);
        if (fragmentos.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "El documento '" + nombre + "' no produjo fragmentos");
        }

        float[][] embeddings = embed(fragmentos, nombre);
        long documentoId = repository.saveDocument(nombre, tipo, contenido, hash);
        repository.saveFragments(documentoId, fragmentos, embeddings);

        log.info("Documento ingerido: id={}, nombre={}, fragmentos={}", documentoId, nombre, fragmentos.size());
        return new IngestionResult(documentoId, fragmentos.size(), false);
    }

    /**
     * Genera embeddings en lotes. Ollama procesa mejor en lotes que de uno en uno.
     */
    private float[][] embed(List<String> fragmentos, String nombre) {
        int batchSize = 16;
        List<float[]> embeddings = new ArrayList<>(fragmentos.size());

        for (int i = 0; i < fragmentos.size(); i += batchSize) {
            int end = Math.min(i + batchSize, fragmentos.size());
            embeddings.addAll(embeddingModel.embed(fragmentos.subList(i, end)));
        }
        return embeddings.toArray(new float[0][]);
    }

    public boolean delete(long documentoId) {
        if (repository.findNombreById(documentoId).isEmpty()) {
            return false;
        }
        repository.deleteDocument(documentoId);
        return true;
    }
}
