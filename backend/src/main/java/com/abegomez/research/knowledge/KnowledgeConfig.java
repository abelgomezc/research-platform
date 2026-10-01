package com.abegomez.research.knowledge;

import com.abegomez.research.common.health.LocalRagHealthIndicator;
import com.abegomez.research.knowledge.localrag.LocalRagKnowledgeSearchAdapter;
import com.abegomez.research.knowledge.pgvector.DocumentChunkRepository;
import com.abegomez.research.knowledge.pgvector.PgVectorKnowledgeSearchAdapter;
import com.abegomez.research.knowledge.pgvector.TextChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selecciona la implementacion de conocimiento interno segun
 * {@code app.knowledge.provider}.
 *
 * <p>El agente solo recibe un {@link KnowledgeSearchPort}. Que este sea pgvector
 * o LocalRAG es invisible para el agente: es el objetivo del puerto.
 */
@Configuration
public class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);

    @Bean
    public TextChunker textChunker(KnowledgeProperties properties) {
        var config = properties.getPgvector();
        return new TextChunker(config.getChunkSize(), config.getChunkOverlap());
    }

    @Bean
    @ConditionalOnProperty(name = "app.knowledge.provider", havingValue = "pgvector",
            matchIfMissing = true)
    public KnowledgeSearchPort pgVectorKnowledgeSearchPort(DocumentChunkRepository repository,
                                                           EmbeddingModel embeddingModel,
                                                           KnowledgeProperties properties) {
        log.info("Conocimiento interno: proveedor activo pgvector (similitud coseno, topK por defecto {})",
                properties.getPgvector().getDefaultTopK());
        return new PgVectorKnowledgeSearchAdapter(repository, embeddingModel,
                properties.getPgvector().getDefaultTopK());
    }

    @Bean
    @ConditionalOnProperty(name = "app.knowledge.provider", havingValue = "localrag")
    public KnowledgeSearchPort localRagKnowledgeSearchPort(KnowledgeProperties properties,
                                                           ObjectMapper objectMapper) {
        var config = properties.getLocalrag();
        log.info("Conocimiento interno: proveedor activo localrag ({}{}). "
                        + "Si LocalRAG no expone el endpoint de busqueda, las llamadas fallaran de forma controlada.",
                config.getBaseUrl(), config.getSearchPath());
        return new LocalRagKnowledgeSearchAdapter(config.getBaseUrl(), config.getSearchPath(),
                config.getTimeout(), objectMapper);
    }

    /**
     * Health del adaptador LocalRAG. Solo se registra cuando ese proveedor esta
     * activo, para no reportar DOWN por un proveedor que no se esta usando.
     */
    @Bean
    @ConditionalOnProperty(name = "app.knowledge.provider", havingValue = "localrag")
    public LocalRagHealthIndicator localRagHealthIndicator(KnowledgeProperties properties) {
        return new LocalRagHealthIndicator(properties.getLocalrag());
    }
}
