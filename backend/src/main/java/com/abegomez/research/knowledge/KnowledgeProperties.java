package com.abegomez.research.knowledge;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del conocimiento interno: proveedor activo y parametros de cada
 * implementacion.
 */
@ConfigurationProperties(prefix = "app.knowledge")
public class KnowledgeProperties {

    public enum Provider {
        PGVECTOR,
        LOCALRAG;

        public static Provider from(String value) {
            if (value == null || value.isBlank()) {
                return PGVECTOR;
            }
            try {
                return Provider.valueOf(value.trim().toUpperCase());
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "Proveedor de conocimiento desconocido: '" + value + "'. Use pgvector o localrag.");
            }
        }
    }

    /**
     * Implementacion activa.
     */
    private Provider provider = Provider.PGVECTOR;

    private PgVector pgvector = new PgVector();

    private LocalRag localrag = new LocalRag();

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public PgVector getPgvector() {
        return pgvector;
    }

    public void setPgvector(PgVector pgvector) {
        this.pgvector = pgvector;
    }

    public LocalRag getLocalrag() {
        return localrag;
    }

    public void setLocalrag(LocalRag localrag) {
        this.localrag = localrag;
    }

    public static class PgVector {

        /**
         * Dimension del vector. Debe coincidir con la del modelo de embeddings y
         * con la columna {@code embedding} de la migracion.
         */
        private int embeddingDimensions = 768;

        private int chunkSize = 800;

        private int chunkOverlap = 120;

        private int defaultTopK = 5;

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }
    }

    public static class LocalRag {

        private String baseUrl = "http://localhost:8080";

        /**
         * Ruta del endpoint de busqueda de fragmentos.
         */
        private String searchPath = "/api/search";

        private Duration timeout = Duration.ofSeconds(15);

        /**
         * Cuando es true se verifica al arrancar que el endpoint exista. Por
         * defecto false porque hoy LocalRAG no lo expone y no debe impedir el
         * arranque de la plataforma.
         */
        private boolean enabledCheck = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSearchPath() {
            return searchPath;
        }

        public void setSearchPath(String searchPath) {
            this.searchPath = searchPath;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public boolean isEnabledCheck() {
            return enabledCheck;
        }

        public void setEnabledCheck(boolean enabledCheck) {
            this.enabledCheck = enabledCheck;
        }
    }
}
