package com.abegomez.research.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del metabuscador local usado por la tool de busqueda web.
 */
@ConfigurationProperties(prefix = "app.search")
public class SearchProperties {

    /**
     * URL base de SearXNG.
     */
    private String baseUrl = "http://localhost:8081";

    /**
     * Tiempo maximo de espera por consulta.
     */
    private Duration timeout = Duration.ofSeconds(15);

    /**
     * Numero maximo de resultados devueltos por consulta.
     */
    private int maxResults = 8;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }
}
